package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.transfer.DraftMaterialSource
import com.gvart.parleyroom.ai.transfer.DraftWord
import com.gvart.parleyroom.ai.transfer.TextSourceKind
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Material text for a words-from-material generation. */
data class MaterialPrompt(val text: String, val maxWords: Int, val excludeWords: List<String>)

/** A material's text as sent to the model. */
data class MaterialSource(val id: UUID, val name: String, val kind: TextSourceKind, val text: String, val truncated: Boolean) {
    fun ref() = DraftMaterialSource(id.toString(), name, kind, text.length, truncated)
}

/** What the student already has: dropped from generated words and listed in `<exclude_words>`. */
data class StudentWords(val entryIds: Set<UUID>, val lemmas: Set<String>, val display: List<String>) {
    fun has(word: DraftWord): Boolean =
        word.libraryEntryId?.let { UUID.fromString(it) in entryIds } == true || word.entry.lemma.trim().lowercase() in lemmas
}

/**
 * Materials as the main source of a student draft (words to learn from a worksheet or article). A
 * material must be in the teacher's own library; sharing with the student is not required.
 * Text is read with [MaterialText.Limits.WORDS] and all materials together share [TOTAL_CHARS].
 */
class MaterialSources(private val storage: StorageService) {

    /** The teacher's file materials in request order. Must run in a transaction. */
    fun requireFiles(teacherId: UUID, ids: List<UUID>): List<ResultRow> {
        val rows = MaterialTable.selectAll()
            .where { (MaterialTable.id inList ids) and (MaterialTable.teacherId eq teacherId) }
            .associateBy { it[MaterialTable.id].value }
        return ids.map { id ->
            val row = rows[id] ?: throw NotFoundException("Material not found", code = "MATERIAL_NOT_FOUND")
            if (kindOf(row) == null)
                throw BadRequestException("\"${row[MaterialTable.name]}\" is not a PDF, DOCX or text file", code = "AI_MATERIAL_UNSUPPORTED")
            row
        }
    }

    /**
     * Extracts the text of [rows] (outside a transaction: it reads the files). With [requireText] a
     * material without text fails the request; otherwise (a refine) it is left out.
     */
    fun extract(rows: List<ResultRow>, requireText: Boolean): List<MaterialSource> {
        val extracted = rows.mapNotNull { row ->
            val kind = kindOf(row) ?: return@mapNotNull null
            val text = MaterialText.extract(kind, row[MaterialTable.fileSize], MaterialText.Limits.WORDS) { storage.stream(row[MaterialTable.url]) }
            if (text.kind == TextSourceKind.NAME_ONLY) {
                if (requireText) throw BadRequestException("No text could be read from \"${row[MaterialTable.name]}\"", code = "AI_MATERIAL_NO_TEXT")
                return@mapNotNull null
            }
            MaterialSource(row[MaterialTable.id].value, row[MaterialTable.name], text.kind, text.text, text.truncated || text.pagesCut)
        }
        return withinBudget(extracted)
    }

    companion object {
        const val MAX_MATERIALS = 5
        const val WORDS_PER_MATERIAL = 40
        /** All materials of one generation together (~15k input tokens). */
        const val TOTAL_CHARS = 60_000
        const val MAX_EXCLUDE_WORDS = 2_000

        private fun kindOf(row: ResultRow): TextSourceKind? {
            val key = row[MaterialTable.url]
            if (row[MaterialTable.type] != MaterialType.PDF || key.isBlank()) return null
            return MaterialText.kindOf(row[MaterialTable.contentType], key.substringAfterLast('/'))
        }

        /** Fair split of [TOTAL_CHARS]: short materials keep all their text, the long ones share the rest. */
        fun withinBudget(sources: List<MaterialSource>): List<MaterialSource> {
            var remaining = TOTAL_CHARS
            val allowed = mutableMapOf<Int, Int>()
            sources.withIndex().sortedBy { it.value.text.length }.forEachIndexed { rank, (index, source) ->
                val share = remaining / (sources.size - rank)
                allowed[index] = minOf(source.text.length, share)
                remaining -= allowed.getValue(index)
            }
            return sources.mapIndexed { index, source ->
                val (text, cut) = MaterialText.cap(source.text, allowed.getValue(index))
                if (cut) source.copy(text = text, truncated = true) else source
            }
        }

        /** The materials as one prompt text, each headed by its name. */
        fun promptText(sources: List<MaterialSource>): String = sources.withIndex().joinToString("\n\n") { (i, source) ->
            val note = if (source.truncated) " (truncated: only the beginning is included)" else ""
            "Material ${i + 1}: ${source.name}$note\n${source.text}"
        }

        /** Every word the student has (from any teacher). Must run in a transaction. */
        fun studentWords(studentId: UUID): StudentWords {
            val rows = (StudentVocabTable innerJoin VocabEntryTable)
                .select(VocabEntryTable.id, VocabEntryTable.article, VocabEntryTable.lemma)
                .where { StudentVocabTable.studentId eq studentId }
                .orderBy(StudentVocabTable.addedAt, SortOrder.DESC)
                .toList()
            return StudentWords(
                entryIds = rows.map { it[VocabEntryTable.id].value }.toSet(),
                lemmas = rows.map { it[VocabEntryTable.lemma].trim().lowercase() }.toSet(),
                display = rows.take(MAX_EXCLUDE_WORDS).map { row ->
                    listOfNotNull(row[VocabEntryTable.article]?.name?.lowercase(), row[VocabEntryTable.lemma]).joinToString(" ")
                },
            )
        }
    }
}

package com.gvart.parleyroom.document.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.document.data.DocumentGroupTable
import com.gvart.parleyroom.document.data.DocumentLessonTable
import com.gvart.parleyroom.document.data.DocumentStudentTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentSummary
import com.gvart.parleyroom.document.transfer.DocumentVocabEntry
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabDisplay
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/** Access rules and response rendering for documents. Must be called inside a transaction. */
class DocumentSupport {

    fun findDocument(documentId: UUID): ResultRow = DocumentTable.findByIdOrThrow(documentId, "Document")

    /** Owner-only writes: each teacher edits their own library documents. */
    fun requireOwned(documentId: UUID, principal: UserPrincipal): ResultRow {
        val row = findDocument(documentId)
        if (principal.role != UserRole.TEACHER || row[DocumentTable.ownerId].value != principal.id)
            throw ForbiddenException("Only the owning teacher can change this document")
        return row
    }

    /** Owner or admin: reads of teacher-only data (versions) and deletes. */
    fun requireOwnedOrAdmin(documentId: UUID, principal: UserPrincipal): ResultRow {
        val row = findDocument(documentId)
        if (principal.role == UserRole.ADMIN) return row
        return requireOwned(documentId, principal)
    }

    /** Students without access get 404 so they cannot probe for documents. */
    fun requireReadable(documentId: UUID, principal: UserPrincipal): ResultRow {
        val row = findDocument(documentId)
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> if (row[DocumentTable.ownerId].value != principal.id)
                throw ForbiddenException("Not your document")
            UserRole.STUDENT -> if (documentId !in readableByStudent(principal.id))
                throw NotFoundException("Document not found", code = "DOCUMENT_NOT_FOUND")
        }
        return row
    }

    fun readableByStudent(studentId: UUID): Set<UUID> = DocumentAccess.readableByStudent(studentId)

    /** Every write to a document bumps its revision so editors and live viewers notice. */
    fun bumpRevision(documentId: UUID) {
        DocumentTable.update({ DocumentTable.id eq documentId }) {
            it[revision] = DocumentTable.revision + 1
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun toResponse(row: ResultRow, principal: UserPrincipal): DocumentResponse {
        val id = row[DocumentTable.id].value
        val links = Links.load(listOf(id))
        val isStudent = principal.role == UserRole.STUDENT
        val blocks = row[DocumentTable.blocks]
        return DocumentResponse(
            id = id.toString(),
            ownerId = row[DocumentTable.ownerId].value.toString(),
            title = row[DocumentTable.title],
            level = row[DocumentTable.level],
            topicIds = links.topics(id),
            grammarTopicIds = links.grammarTopics(id),
            audience = row[DocumentTable.audience],
            studentIds = if (isStudent) emptyList() else links.students(id),
            groupIds = if (isStudent) emptyList() else links.groups(id),
            lessonIds = links.lessons(id),
            createdFromLessonId = row[DocumentTable.createdFromLessonId]?.value?.toString(),
            revision = row[DocumentTable.revision],
            blocks = if (isStudent) stripSolutions(blocks) else blocks,
            vocab = renderVocab(blocks, row, principal),
            createdAt = row[DocumentTable.createdAt],
            updatedAt = row[DocumentTable.updatedAt],
        )
    }

    fun toSummaries(rows: List<ResultRow>, principal: UserPrincipal): List<DocumentSummary> {
        val links = Links.load(rows.map { it[DocumentTable.id].value })
        val isStudent = principal.role == UserRole.STUDENT
        return rows.map { row ->
            val id = row[DocumentTable.id].value
            DocumentSummary(
                id = id.toString(),
                ownerId = row[DocumentTable.ownerId].value.toString(),
                title = row[DocumentTable.title],
                level = row[DocumentTable.level],
                topicIds = links.topics(id),
                grammarTopicIds = links.grammarTopics(id),
                audience = row[DocumentTable.audience],
                studentIds = if (isStudent) emptyList() else links.students(id),
                groupIds = if (isStudent) emptyList() else links.groups(id),
                lessonIds = links.lessons(id),
                createdFromLessonId = row[DocumentTable.createdFromLessonId]?.value?.toString(),
                revision = row[DocumentTable.revision],
                blockCount = row[DocumentTable.blocks].size,
                createdAt = row[DocumentTable.createdAt],
                updatedAt = row[DocumentTable.updatedAt],
            )
        }
    }

    /** Removes every `solution` (answer key) from the blocks. */
    fun stripSolutions(blocks: JsonArray): JsonArray = strip(blocks) as JsonArray

    private fun strip(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { it != SOLUTION }.mapValues { (_, value) -> strip(value) })
        is JsonArray -> JsonArray(element.map(::strip))
        else -> element
    }

    /** Vocab entries referenced by vocab_table rows, filtered by the viewer's display setting. */
    private fun renderVocab(blocks: JsonArray, row: ResultRow, principal: UserPrincipal): List<DocumentVocabEntry> {
        val entryIds = blocks.asSequence()
            .map { it.jsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.content == "vocab_table" }
            .flatMap { it["rows"]?.jsonArray.orEmpty() }
            .map { UUID.fromString((it.jsonObject["vocabEntryId"] as JsonPrimitive).content) }
            .distinct()
            .toList()
        if (entryIds.isEmpty()) return emptyList()

        val ownerId = row[DocumentTable.ownerId].value
        val display = if (principal.role == UserRole.STUDENT)
            studentDisplay(ownerId, principal.id, row[DocumentTable.createdFromLessonId]?.value)
        else null

        return VocabEntryTable.selectAll()
            .where { (VocabEntryTable.id inList entryIds) and (VocabEntryTable.teacherId eq ownerId) }
            .map { entry ->
                val all = entry[VocabEntryTable.translations]
                DocumentVocabEntry(
                    id = entry[VocabEntryTable.id].value.toString(),
                    lemma = entry[VocabEntryTable.lemma],
                    article = entry[VocabEntryTable.article],
                    plural = entry[VocabEntryTable.plural],
                    wordType = entry[VocabEntryTable.wordType],
                    forms = entry[VocabEntryTable.forms],
                    government = entry[VocabEntryTable.government],
                    exampleSentence = entry[VocabEntryTable.exampleSentence],
                    level = entry[VocabEntryTable.level],
                    display = display,
                    translations = if (display == null) all else all.filterKeys { it in display.fields },
                    explanationDe = entry[VocabEntryTable.explanationDe]
                        .takeIf { display == null || VocabDisplay.DE_EXPLANATION in display.fields },
                    revealTranslations = if (display != null && display.allowTranslationToggle)
                        all.filterKeys { it !in display.fields }.takeIf { it.isNotEmpty() }
                    else null,
                )
            }
    }

    /** Lesson override (document's source lesson) > teacher–student setting > level default. */
    private fun studentDisplay(teacherId: UUID, studentId: UUID, lessonId: UUID?): VocabDisplaySetting {
        lessonId?.let { id ->
            LessonTable.selectAll().where { LessonTable.id eq id }.singleOrNull()
                ?.let { VocabDisplay.of(it[LessonTable.vocabDisplayFields], it[LessonTable.allowTranslationToggle]) }
                ?.let { return it }
        }
        TeacherStudentTable.selectAll()
            .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId eq studentId) }
            .singleOrNull()
            ?.let { VocabDisplay.of(it[TeacherStudentTable.vocabDisplayFields], it[TeacherStudentTable.allowTranslationToggle]) }
            ?.let { return it }
        val level: LanguageLevel? = UserTable.select(UserTable.level).where { UserTable.id eq studentId }.singleOrNull()?.get(UserTable.level)
        return VocabDisplay.defaultFor(level)
    }

    /** Batch-loaded link tables for a set of documents. */
    private class Links(
        private val topics: Map<UUID, List<String>>,
        private val grammarTopics: Map<UUID, List<String>>,
        private val students: Map<UUID, List<String>>,
        private val groups: Map<UUID, List<String>>,
        private val lessons: Map<UUID, List<String>>,
    ) {
        fun topics(id: UUID) = topics[id].orEmpty()
        fun grammarTopics(id: UUID) = grammarTopics[id].orEmpty()
        fun students(id: UUID) = students[id].orEmpty()
        fun groups(id: UUID) = groups[id].orEmpty()
        fun lessons(id: UUID) = lessons[id].orEmpty()

        companion object {
            fun load(ids: List<UUID>) = Links(
                topics = ids(DocumentTopicTable, DocumentTopicTable.documentId, DocumentTopicTable.topicId, ids),
                grammarTopics = ids(DocumentGrammarTopicTable, DocumentGrammarTopicTable.documentId, DocumentGrammarTopicTable.grammarTopicId, ids),
                students = ids(DocumentStudentTable, DocumentStudentTable.documentId, DocumentStudentTable.studentId, ids),
                groups = ids(DocumentGroupTable, DocumentGroupTable.documentId, DocumentGroupTable.groupId, ids),
                lessons = ids(DocumentLessonTable, DocumentLessonTable.documentId, DocumentLessonTable.lessonId, ids),
            )

            private fun ids(
                table: Table,
                documentColumn: org.jetbrains.exposed.v1.core.Column<EntityID<UUID>>,
                valueColumn: org.jetbrains.exposed.v1.core.Column<EntityID<UUID>>,
                documentIds: List<UUID>,
            ): Map<UUID, List<String>> {
                if (documentIds.isEmpty()) return emptyMap()
                return table.select(documentColumn, valueColumn)
                    .where { documentColumn inList documentIds }
                    .groupBy({ it[documentColumn].value }) { it[valueColumn].value.toString() }
            }
        }
    }

    companion object {
        const val SOLUTION = "solution"
    }
}

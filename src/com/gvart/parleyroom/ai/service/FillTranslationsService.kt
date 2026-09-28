package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.transfer.FillMissingRequest
import com.gvart.parleyroom.ai.transfer.FillTranslationsResult
import com.gvart.parleyroom.ai.transfer.FilledEntry
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.ai.transfer.MissingFieldsResponse
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.common.transfer.exception.ServiceUnavailableException
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabDisplay
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/** Brief §5.3: generate a missing translation / explanation on demand. Only empty fields are filled. */
class FillTranslationsService(
    private val ai: AiRuntime,
    private val runner: GenerationJobRunner,
) {

    /** Entries of the teacher's library assigned to the student that lack any of [fields]. */
    fun missingFields(studentId: UUID, fields: List<String>, principal: UserPrincipal): MissingFieldsResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
        VocabDisplay.requireSupportedFields(VocabDisplaySetting(fields, false))
        val ids = (StudentVocabTable innerJoin VocabEntryTable).selectAll()
            .where { (StudentVocabTable.studentId eq studentId) and (VocabEntryTable.teacherId eq principal.id) }
            .orderBy(VocabEntryTable.lemma)
            .filter { missing(it, fields).isNotEmpty() }
            .map { it[VocabEntryTable.id].value.toString() }
        MissingFieldsResponse(ids.size, ids)
    }

    fun start(request: FillMissingRequest, principal: UserPrincipal): GenerationJobResponse {
        val gateway = ai.gateway
            ?: throw ServiceUnavailableException("AI is not configured on this server", code = "AI_NOT_CONFIGURED")
        val entryIds = request.entryIds.map(UUID::fromString).distinct()
        transaction {
            LibraryAccess.requireTeacher(principal)
            VocabDisplay.requireSupportedFields(VocabDisplaySetting(request.fields, false))
            val owned = VocabEntryTable.selectAll()
                .where { (VocabEntryTable.id inList entryIds) and (VocabEntryTable.teacherId eq principal.id) }
                .count()
            if (owned != entryIds.size.toLong()) throw NotFoundException("One or more vocab entries not found", code = "VOCAB_ENTRY_NOT_FOUND")
        }
        val input = JobInput(entryIds = entryIds.map(UUID::toString), fields = request.fields.distinct())
        val jobId = runner.enqueue(principal.id, null, GenerationJobKind.FILL_TRANSLATIONS, GenerationJobs.json.encodeToJsonElement(input), modelId = gateway.modelId)
        runner.launch(jobId) { run(gateway, entryIds, input.fields!!) }
        return transaction { GenerationJobs.toResponse(GenerationJobs.requireReadable(jobId, principal)) }
    }

    private suspend fun run(gateway: LlmGateway, entryIds: List<UUID>, fields: List<String>): JobSuccess {
        // Only what describes the word itself is sent: no student data.
        val todo = transaction {
            VocabEntryTable.selectAll().where { VocabEntryTable.id inList entryIds }.orderBy(VocabEntryTable.lemma)
                .map { it to missing(it, fields) }
        }
        val needed = todo.filter { it.second.isNotEmpty() }
        val skipped = todo.filter { it.second.isEmpty() }.map { it.first[VocabEntryTable.id].value.toString() }
        if (needed.isEmpty()) {
            return JobSuccess(GenerationJobs.json.encodeToJsonElement(FillTranslationsResult(emptyList(), skipped)), 0, Usage())
        }

        val keyed = needed.mapIndexed { i, (row, missing) -> Triple("e${i + 1}", row, missing) }
        val entries = JsonArray(keyed.map { (key, row, missing) ->
            buildJsonObject {
                put("key", key)
                put("lemma", row[VocabEntryTable.lemma])
                row[VocabEntryTable.article]?.let { put("article", it.name) }
                row[VocabEntryTable.plural]?.let { put("plural", it) }
                put("wordType", row[VocabEntryTable.wordType].name)
                row[VocabEntryTable.forms]?.let { put("forms", it) }
                row[VocabEntryTable.government]?.let { put("government", it) }
                row[VocabEntryTable.exampleSentence]?.let { put("exampleSentence", it) }
                row[VocabEntryTable.level]?.let { put("level", it.name) }
                put("missing", JsonArray(missing.map(::JsonPrimitive)))
            }
        })
        val completion = GenerationJobs.completeValidated(
            gateway, Prompts.fillSystem, Prompts.fill(entries.toString(), fields), MAX_TOKENS,
        ) { AiOutputParser.parseFill(it, keyed.map { k -> k.first }.toSet()) }

        val byKey = completion.value.entries.associateBy { it.key }
        val updated = transaction {
            keyed.mapNotNull { (key, row, missing) ->
                val answer = byKey[key] ?: return@mapNotNull null
                val id = row[VocabEntryTable.id].value
                // Re-read: never overwrite a field that was filled meanwhile.
                val current = VocabEntryTable.selectAll().where { VocabEntryTable.id eq id }.singleOrNull() ?: return@mapNotNull null
                val stillMissing = missing(current, missing)
                val translations = current[VocabEntryTable.translations].toMutableMap()
                val filled = mutableListOf<String>()
                stillMissing.filter { it in VocabDisplay.TRANSLATION_LANGUAGES }.forEach { language ->
                    answer.translations[language]?.takeIf { it.isNotBlank() }?.let { translations[language] = it.trim(); filled += language }
                }
                val explanation = answer.explanationDe?.takeIf { VocabDisplay.DE_EXPLANATION in stillMissing && it.isNotBlank() }?.trim()
                if (explanation != null) filled += VocabDisplay.DE_EXPLANATION
                if (filled.isEmpty()) return@mapNotNull null
                VocabEntryTable.update({ VocabEntryTable.id eq id }) {
                    it[VocabEntryTable.translations] = translations
                    if (explanation != null) it[explanationDe] = explanation
                    it[updatedAt] = OffsetDateTime.now()
                }
                FilledEntry(id.toString(), filled)
            }
        }
        val result = FillTranslationsResult(updated, skipped)
        return JobSuccess(GenerationJobs.json.encodeToJsonElement(result), completion.attempts, completion.usage)
    }

    private fun missing(row: ResultRow, fields: List<String>): List<String> = fields.filter { field ->
        when (field) {
            VocabDisplay.DE_EXPLANATION -> row[VocabEntryTable.explanationDe].isNullOrBlank()
            else -> row[VocabEntryTable.translations][field].isNullOrBlank()
        }
    }

    companion object {
        const val MAX_TOKENS = 8_000
    }
}

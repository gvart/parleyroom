package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.transfer.FillMissingRequest
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.transfer.ApplyFillProposalsRequest
import com.gvart.parleyroom.ai.transfer.FillProposal
import com.gvart.parleyroom.ai.transfer.FillProposalStatus
import com.gvart.parleyroom.ai.transfer.FillProposalsResult
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.ai.transfer.MissingFieldsResponse
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
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
import kotlinx.serialization.json.decodeFromJsonElement
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

/**
 * Brief §5.3: AI proposals for missing translations / explanations. The job only proposes; the
 * teacher applies the selected proposals (or rejects them) and only then vocab_entries change.
 */
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
            return JobSuccess(GenerationJobs.json.encodeToJsonElement(FillProposalsResult(emptyList(), skipped)), 0, Usage())
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

        // Proposals only: vocab_entries are shared by every student who has the word, so nothing is
        // written before the teacher approves (see apply).
        val byKey = completion.value.entries.associateBy { it.key }
        var next = 0
        val proposals = keyed.flatMap { (key, row, missing) ->
            val answer = byKey[key] ?: return@flatMap emptyList()
            val entryId = row[VocabEntryTable.id].value.toString()
            val lemma = row[VocabEntryTable.lemma]
            missing.mapNotNull { field ->
                val value = when (field) {
                    VocabDisplay.DE_EXPLANATION -> answer.explanationDe
                    else -> answer.translations[field]
                }?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                FillProposal("p${++next}", entryId, lemma, field, current(row, field), value)
            }
        }
        val result = FillProposalsResult(proposals, skipped)
        return JobSuccess(GenerationJobs.json.encodeToJsonElement(result), completion.attempts, completion.usage)
    }

    /**
     * Writes the selected proposals (optionally with the teacher's own value). A field that is no
     * longer empty is not overwritten: its proposal is reported as stale. Unselected proposals are dropped.
     */
    fun apply(jobId: UUID, request: ApplyFillProposalsRequest, principal: UserPrincipal): GenerationJobResponse = transaction {
        val (job, result) = pending(jobId, principal)
        val byId = result.proposals.associateBy { it.id }
        request.proposals.map { it.id }.filter { it !in byId }.takeIf { it.isNotEmpty() }?.let {
            throw BadRequestException("Unknown proposals: ${it.joinToString()}", code = "VALIDATION_FAILED")
        }
        val applied = mutableListOf<String>()
        val stale = mutableListOf<String>()
        request.proposals.groupBy { byId.getValue(it.id).entryId }.forEach { (entryId, decisions) ->
            val row = VocabEntryTable.selectAll()
                .where { (VocabEntryTable.id eq UUID.fromString(entryId)) and (VocabEntryTable.teacherId eq principal.id) }
                .singleOrNull()
            if (row == null) { stale += decisions.map { it.id }; return@forEach }
            val translations = row[VocabEntryTable.translations].toMutableMap()
            var explanation: String? = null
            decisions.forEach { decision ->
                val proposal = byId.getValue(decision.id)
                if (missing(row, listOf(proposal.field)).isEmpty()) { stale += proposal.id; return@forEach }
                val value = (decision.value ?: proposal.proposedValue).trim()
                if (proposal.field == VocabDisplay.DE_EXPLANATION) explanation = value else translations[proposal.field] = value
                applied += proposal.id
            }
            if (decisions.any { it.id in applied }) VocabEntryTable.update({ VocabEntryTable.id eq row[VocabEntryTable.id] }) {
                it[VocabEntryTable.translations] = translations
                explanation?.let { text -> it[explanationDe] = text }
                it[updatedAt] = OffsetDateTime.now()
            }
        }
        resolve(job, result.copy(status = FillProposalStatus.APPLIED, applied = applied, stale = stale), principal)
    }

    fun reject(jobId: UUID, principal: UserPrincipal): GenerationJobResponse = transaction {
        val (job, result) = pending(jobId, principal)
        resolve(job, result.copy(status = FillProposalStatus.REJECTED), principal)
    }

    private fun pending(jobId: UUID, principal: UserPrincipal): Pair<ResultRow, FillProposalsResult> {
        val job = GenerationJobs.requireOwned(jobId, principal)
        if (job[GenerationJobTable.kind] != GenerationJobKind.FILL_TRANSLATIONS)
            throw ConflictException("Not a fill-missing job", code = "AI_JOB_NOT_READY")
        GenerationJobs.requireSucceeded(job)
        val result = GenerationJobs.json.decodeFromJsonElement<FillProposalsResult>(job[GenerationJobTable.result]!!)
        if (result.status != FillProposalStatus.PENDING)
            throw ConflictException("The proposals were already ${result.status.name.lowercase()}", code = "AI_PROPOSALS_RESOLVED")
        return job to result
    }

    private fun resolve(job: ResultRow, result: FillProposalsResult, principal: UserPrincipal): GenerationJobResponse {
        val jobId = job[GenerationJobTable.id].value
        GenerationJobTable.update({ GenerationJobTable.id eq jobId }) {
            it[GenerationJobTable.result] = GenerationJobs.json.encodeToJsonElement(result.copy(resolvedAt = OffsetDateTime.now()))
        }
        return GenerationJobs.toResponse(GenerationJobs.requireOwned(jobId, principal))
    }

    private fun current(row: ResultRow, field: String): String? = when (field) {
        VocabDisplay.DE_EXPLANATION -> row[VocabEntryTable.explanationDe]
        else -> row[VocabEntryTable.translations][field]
    }?.takeIf { it.isNotBlank() }

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

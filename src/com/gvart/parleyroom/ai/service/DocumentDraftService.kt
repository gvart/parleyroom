package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.DocumentDraftTable
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.transfer.DocumentDraftInput
import com.gvart.parleyroom.ai.transfer.DocumentDraftResponse
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import com.gvart.parleyroom.document.service.DocumentService
import com.gvart.parleyroom.document.service.DocumentSupport
import com.gvart.parleyroom.document.service.DocumentVersionService
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.UpdateDocumentRequest
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/**
 * AI refine of an existing document as a **draft revision** (`document_drafts`): the refined
 * title + blocks never touch `documents`, so students keep reading the published content until the
 * teacher publishes the draft (snapshot AI_REFINE, then the normal update path) or discards it.
 */
class DocumentDraftService(
    private val ai: AiRuntime,
    private val runner: GenerationJobRunner,
    private val support: DocumentSupport,
    private val documentService: DocumentService,
    private val versions: DocumentVersionService,
) {

    fun refine(documentId: UUID, instruction: String, principal: UserPrincipal): GenerationJobResponse {
        val gateway = GenerationJobs.requireGateway(ai)
        transaction { support.requireOwned(documentId, principal) }
        val input = JobInput(instruction = instruction)
        val jobId = runner.enqueue(principal.id, null, GenerationJobKind.REFINE, GenerationJobs.json.encodeToJsonElement(input),
            documentId = documentId, modelId = gateway.modelId)
        runner.launch(jobId) { runRefine(gateway, jobId, documentId, instruction, principal) }
        return transaction { GenerationJobs.toResponse(GenerationJobs.requireReadable(jobId, principal)) }
    }

    fun get(documentId: UUID, principal: UserPrincipal): DocumentDraftResponse = transaction {
        val document = support.requireOwned(documentId, principal)
        toResponse(requireDraft(documentId), document)
    }

    /** Like [get], but null instead of 404 when there is no draft. */
    fun find(documentId: UUID, principal: UserPrincipal): DocumentDraftResponse? = transaction {
        val document = support.requireOwned(documentId, principal)
        DocumentDraftTable.selectAll().where { DocumentDraftTable.documentId eq documentId }.singleOrNull()?.let { toResponse(it, document) }
    }

    /** Manual edits of the draft before publishing. */
    fun update(documentId: UUID, input: DocumentDraftInput, principal: UserPrincipal): DocumentDraftResponse = transaction {
        val document = support.requireOwned(documentId, principal)
        requireDraft(documentId)
        DocumentBlockValidator.validate(input.blocks)
        DocumentDraftTable.update({ DocumentDraftTable.documentId eq documentId }) {
            it[title] = input.title.trim()
            it[blocks] = input.blocks
        }
        toResponse(requireDraft(documentId), document)
    }

    /** The draft becomes the document's content (full validation, revision +1); the previous content is kept as a version. */
    fun publish(documentId: UUID, principal: UserPrincipal): DocumentResponse = transaction {
        val document = support.requireOwned(documentId, principal)
        val draft = requireDraft(documentId)
        versions.snapshot(document, DocumentVersionReason.AI_REFINE, principal)
        val topicIds = DocumentTopicTable.select(DocumentTopicTable.topicId).where { DocumentTopicTable.documentId eq documentId }
            .map { it[DocumentTopicTable.topicId].value.toString() }
        val grammarIds = DocumentGrammarTopicTable.select(DocumentGrammarTopicTable.grammarTopicId)
            .where { DocumentGrammarTopicTable.documentId eq documentId }.map { it[DocumentGrammarTopicTable.grammarTopicId].value.toString() }
        val published = documentService.updateDocument(
            documentId,
            UpdateDocumentRequest(
                title = draft[DocumentDraftTable.title],
                level = document[DocumentTable.level],
                topicIds = topicIds,
                grammarTopicIds = grammarIds,
                audience = document[DocumentTable.audience],
                blocks = draft[DocumentDraftTable.blocks],
                revision = document[DocumentTable.revision],
            ),
            principal,
        )
        DocumentDraftTable.deleteWhere { DocumentDraftTable.documentId eq documentId }
        published
    }

    fun discard(documentId: UUID, principal: UserPrincipal) = transaction {
        support.requireOwned(documentId, principal)
        requireDraft(documentId)
        DocumentDraftTable.deleteWhere { DocumentDraftTable.documentId eq documentId }
    }

    private suspend fun runRefine(gateway: LlmGateway, jobId: UUID, documentId: UUID, instruction: String, principal: UserPrincipal): JobSuccess {
        data class Current(val document: ResultRow, val title: String, val blocks: JsonArray, val keyByEntryId: Map<String, String>, val words: String)
        val current = transaction {
            val document = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
                ?: throw AiJobFailure("DOCUMENT_NOT_FOUND", "The document was deleted")
            // A second refine builds on the pending draft.
            val draft = DocumentDraftTable.selectAll().where { DocumentDraftTable.documentId eq documentId }.singleOrNull()
            val blocks = draft?.get(DocumentDraftTable.blocks) ?: document[DocumentTable.blocks]
            val entryIds = blocks.flatMap { block ->
                ((block as JsonObject)["rows"] as? JsonArray).orEmpty().mapNotNull { ((it as JsonObject)["vocabEntryId"] as? JsonPrimitive)?.content }
            }.distinct()
            val keyByEntryId = entryIds.withIndex().associate { (i, id) -> id to "w${i + 1}" }
            val words = if (entryIds.isEmpty()) "" else VocabEntryTable.selectAll()
                .where { VocabEntryTable.id inList entryIds.map(UUID::fromString) }
                .joinToString("\n") { row ->
                    val key = keyByEntryId.getValue(row[VocabEntryTable.id].value.toString())
                    "$key: " + listOfNotNull(row[VocabEntryTable.article]?.name?.lowercase(), row[VocabEntryTable.lemma]).joinToString(" ")
                }
            Current(document, draft?.get(DocumentDraftTable.title) ?: document[DocumentTable.title], blocks, keyByEntryId, words)
        }
        val currentJson = buildJsonObject {
            put("title", current.title)
            put("blocks", AiBlocks.toAi(current.blocks, current.keyByEntryId, emptyMap()))
        }.toString()
        val request = Prompts.refineDocument(current.document[DocumentTable.level]?.name ?: "unknown", current.words, currentJson, instruction)
        val completion = GenerationJobs.completeValidated(gateway, Prompts.documentRefineSystem, request, DraftBundleService.MAX_TOKENS) {
            AiOutputParser.parseDocumentRefine(it, current.keyByEntryId.values.toSet())
        }
        val output = completion.value
        return transaction {
            val document = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
                ?: throw AiJobFailure("DOCUMENT_NOT_FOUND", "The document was deleted", completion.attempts, completion.usage)
            val entryByKey = current.keyByEntryId.entries.associate { (entryId, key) -> key to entryId }
            val blocks = AiBlocks.fillRows(output.blocks, output.vocabTables.mapValues { (_, keys) -> keys.mapNotNull(entryByKey::get) })
            DocumentDraftTable.upsert {
                it[DocumentDraftTable.documentId] = documentId
                it[title] = output.title
                it[DocumentDraftTable.blocks] = blocks
                it[baseRevision] = document[DocumentTable.revision]
                it[DocumentDraftTable.jobId] = jobId
                it[createdBy] = principal.id
            }
            val result = buildJsonObject { put("documentId", documentId.toString()); put("draft", true) }
            JobSuccess(result, completion.attempts, completion.usage, documentId)
        }
    }

    private fun requireDraft(documentId: UUID): ResultRow = DocumentDraftTable.selectAll()
        .where { DocumentDraftTable.documentId eq documentId }
        .singleOrNull() ?: throw NotFoundException("The document has no draft revision", code = "DOCUMENT_DRAFT_NOT_FOUND")

    private fun toResponse(draft: ResultRow, document: ResultRow) = DocumentDraftResponse(
        documentId = draft[DocumentDraftTable.documentId].value.toString(),
        title = draft[DocumentDraftTable.title],
        blocks = draft[DocumentDraftTable.blocks],
        baseRevision = draft[DocumentDraftTable.baseRevision],
        currentRevision = document[DocumentTable.revision],
        jobId = draft[DocumentDraftTable.jobId]?.value?.toString(),
        createdAt = draft[DocumentDraftTable.createdAt],
        updatedAt = draft[DocumentDraftTable.updatedAt],
    )
}

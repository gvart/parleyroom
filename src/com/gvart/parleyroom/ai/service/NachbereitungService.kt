package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.data.PromptTemplateTable
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.transfer.GenerateRequest
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.GrammarTopicProposal
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.ai.transfer.NachbereitungMode
import com.gvart.parleyroom.ai.transfer.NachbereitungResult
import com.gvart.parleyroom.ai.transfer.NachbereitungState
import com.gvart.parleyroom.ai.transfer.RefineRequest
import com.gvart.parleyroom.ai.transfer.ReviewUpdateRequest
import com.gvart.parleyroom.ai.transfer.ReviewVocabItem
import com.gvart.parleyroom.ai.transfer.TopicProposal
import com.gvart.parleyroom.ai.transfer.VocabTableRef
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.common.transfer.exception.ServiceUnavailableException
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.document.service.DocumentService
import com.gvart.parleyroom.document.service.DocumentVersionService
import com.gvart.parleyroom.document.transfer.CreateDocumentRequest
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabEntryService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/** The Nachbereitung panel: state, generate, refine and review. Publish lives in [NachbereitungPublishService]. */
class NachbereitungService(
    private val ai: AiRuntime,
    private val runner: GenerationJobRunner,
    private val context: LessonContextService,
    private val documentService: DocumentService,
    private val versions: DocumentVersionService,
    private val vocabEntryService: VocabEntryService,
) {

    fun state(lessonId: UUID, principal: UserPrincipal): NachbereitungState = transaction {
        val lesson = context.requireLessonTeacher(lessonId, principal)
        val ctx = context.load(lesson)
        val jobs = GenerationJobTable.selectAll()
            .where { (GenerationJobTable.lessonId eq lessonId) and (GenerationJobTable.kind neq GenerationJobKind.FILL_TRANSLATIONS) }
            .orderBy(GenerationJobTable.createdAt, SortOrder.DESC)
            .toList()
        NachbereitungState(
            lessonId = lessonId.toString(),
            mode = ctx.mode,
            aiAvailable = ai.gateway != null,
            notes = ctx.prefillNotes,
            prompt = ctx.promptUsed,
            context = ctx.summary(),
            latestJob = jobs.firstOrNull()?.let { GenerationJobs.toResponse(it) },
            draftDocumentId = jobs.firstOrNull { it[GenerationJobTable.status] == GenerationJobStatus.SUCCEEDED }
                ?.get(GenerationJobTable.documentId)?.value?.toString(),
            publishedAt = jobs.mapNotNull { it[GenerationJobTable.publishedAt] }.maxOrNull(),
        )
    }

    fun getJob(jobId: UUID, principal: UserPrincipal): GenerationJobResponse = transaction {
        GenerationJobs.toResponse(GenerationJobs.requireReadable(jobId, principal))
    }

    fun generate(lessonId: UUID, request: GenerateRequest, principal: UserPrincipal): GenerationJobResponse {
        val gateway = requireGateway()
        val ctx = transaction {
            LibraryAccess.requireTeacher(principal)
            val lesson = context.requireLessonTeacher(lessonId, principal)
            request.promptTemplateId?.let { id ->
                val found = PromptTemplateTable.selectAll()
                    .where { (PromptTemplateTable.id eq UUID.fromString(id)) and (PromptTemplateTable.teacherId eq principal.id) }
                    .empty().not()
                if (!found) throw NotFoundException("Prompt template not found", code = "PROMPT_TEMPLATE_NOT_FOUND")
            }
            context.load(lesson).also(::requireAttendees)
        }
        val input = JobInput(notes = request.notes, prompt = request.prompt, promptTemplateId = request.promptTemplateId)
        val jobId = runner.enqueue(principal.id, lessonId, GenerationJobKind.GENERATE, GenerationJobs.json.encodeToJsonElement(input), modelId = gateway.modelId)
        runner.launch(jobId) { runGenerate(gateway, ctx, input, principal) }
        return getJob(jobId, principal)
    }

    fun refine(jobId: UUID, request: RefineRequest, principal: UserPrincipal): GenerationJobResponse {
        val gateway = requireGateway()
        val (parent, lessonId) = transaction {
            val parent = GenerationJobs.requireOwned(jobId, principal)
            GenerationJobs.requireSucceeded(parent)
            if (parent[GenerationJobTable.kind] == GenerationJobKind.FILL_TRANSLATIONS)
                throw ConflictException("Only Nachbereitung jobs can be refined", code = "AI_JOB_NOT_READY")
            parent to parent[GenerationJobTable.lessonId]!!.value
        }
        val parentInput = GenerationJobs.input(parent)
        val input = JobInput(notes = parentInput.notes, prompt = parentInput.prompt, instruction = request.instruction)
        val documentId = parent[GenerationJobTable.documentId]?.value
        val newJobId = runner.enqueue(
            principal.id, lessonId, GenerationJobKind.REFINE, GenerationJobs.json.encodeToJsonElement(input),
            parentJobId = jobId, documentId = documentId, modelId = gateway.modelId,
        )
        runner.launch(newJobId) { runRefine(gateway, lessonId, jobId, input, principal) }
        return getJob(newJobId, principal)
    }

    /** Persists the review table (checkboxes, inline edits) so it survives a refresh. */
    fun updateReview(jobId: UUID, request: ReviewUpdateRequest, principal: UserPrincipal): GenerationJobResponse = transaction {
        val row = GenerationJobs.requireOwned(jobId, principal)
        GenerationJobs.requireSucceeded(row)
        val result = nachbereitungResult(row)
        val known = result.vocab.map { it.key }.toSet()
        val unknown = request.vocab.map { it.key }.filter { it !in known }
        if (unknown.isNotEmpty())
            throw BadRequestException("Unknown vocab keys: ${unknown.joinToString()}", code = "VALIDATION_FAILED")
        LibraryAccess.requireTopics(principal.id, request.vocab.flatMap { it.entry.topicIds })
        val updated = result.copy(vocab = request.vocab)
        GenerationJobTable.update({ GenerationJobTable.id eq jobId }) { it[GenerationJobTable.result] = GenerationJobs.json.encodeToJsonElement(updated) }
        GenerationJobs.toResponse(GenerationJobs.requireOwned(jobId, principal))
    }

    private suspend fun runGenerate(gateway: LlmGateway, ctx: LessonContext, input: JobInput, principal: UserPrincipal): JobSuccess {
        val request = Prompts.generate(ctx.mode, ctx.toPromptText(), HtmlText.toPlainText(input.notes.orEmpty()), input.prompt.orEmpty())
        val completion = GenerationJobs.completeValidated(gateway, Prompts.nachbereitungSystem(ctx.mode), request, MAX_TOKENS, AiOutputParser::parseGeneration)
        val output = completion.value
        return transaction {
            val review = review(ctx, output)
            val document = documentService.createDocument(
                CreateDocumentRequest(
                    title = output.title,
                    level = ctx.level,
                    topicIds = review.topics.mapNotNull { it.existingId },
                    grammarTopicIds = review.grammarTopics.mapNotNull { it.existingId },
                    audience = if (ctx.mode == NachbereitungMode.CLUB) DocumentAudience.GROUP else DocumentAudience.STUDENT,
                    blocks = output.blocks,
                ),
                principal,
            )
            // Remember the source lesson without linking: linked documents are readable by attendees.
            val documentId = UUID.fromString(document.id)
            DocumentTable.update({ DocumentTable.id eq documentId }) { it[createdFromLessonId] = ctx.lessonId }
            val result = review.copy(documentId = document.id)
            JobSuccess(GenerationJobs.json.encodeToJsonElement(result), completion.attempts, completion.usage, documentId)
        }
    }

    private suspend fun runRefine(gateway: LlmGateway, lessonId: UUID, parentJobId: UUID, input: JobInput, principal: UserPrincipal): JobSuccess {
        val (ctx, currentOutput, documentId) = transaction {
            val ctx = context.load(LessonTable.selectAll().where { LessonTable.id eq lessonId }.single())
            val parent = GenerationJobTable.selectAll().where { GenerationJobTable.id eq parentJobId }.single()
            val previous = nachbereitungResult(parent)
            val documentId = UUID.fromString(previous.documentId)
            val document = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
                ?: throw AiJobFailure("DOCUMENT_NOT_FOUND", "The draft document was deleted")
            Triple(ctx, currentOutput(previous, document), documentId)
        }
        val (currentText, rowEntries) = currentOutput
        val request = Prompts.refine(ctx.mode, ctx.toPromptText(), HtmlText.toPlainText(input.notes.orEmpty()), input.prompt.orEmpty(), currentText, input.instruction.orEmpty())
        val completion = GenerationJobs.completeValidated(gateway, Prompts.nachbereitungSystem(ctx.mode), request, MAX_TOKENS, AiOutputParser::parseGeneration)
        val output = completion.value
        return transaction {
            val document = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
                ?: throw AiJobFailure("DOCUMENT_NOT_FOUND", "The draft document was deleted", completion.attempts, completion.usage)
            // The refine result wins over concurrent edits; the snapshot keeps the previous state.
            versions.snapshot(document, DocumentVersionReason.AI_REFINE, principal)
            // Words that already had rows (published or added by hand) keep them.
            val keys = output.output.vocab.map { it.key }.toSet()
            val kept = rowEntries.filterKeys { it in keys }
            val blocks = AiBlocks.fillRows(output.blocks, output.vocabTables.mapValues { (_, tableKeys) -> tableKeys.mapNotNull(kept::get) })
            DocumentTable.update({ DocumentTable.id eq documentId }) {
                it[title] = output.title
                it[DocumentTable.blocks] = blocks
                it[revision] = DocumentTable.revision + 1
                it[updatedAt] = OffsetDateTime.now()
            }
            val result = review(ctx, output).copy(documentId = documentId.toString(), publishedEntries = kept)
            JobSuccess(GenerationJobs.json.encodeToJsonElement(result), completion.attempts, completion.usage, documentId)
        }
    }

    /**
     * The previous result and the current draft (incl. Anna's manual edits) in the model's format,
     * plus vocab key -> entry id of every existing vocab_table row. Rows become vocab keys; rows
     * whose entry is not in the result get a new key.
     */
    private fun currentOutput(previous: NachbereitungResult, document: ResultRow): Pair<String, Map<String, String>> {
        val keyByEntryId = mutableMapOf<String, String>()
        previous.vocab.forEach { item -> item.matchedEntryId?.let { keyByEntryId[it] = item.key } }
        previous.publishedEntries.forEach { (key, entryId) -> keyByEntryId[entryId] = key }

        val blocks = document[DocumentTable.blocks]
        val rowEntryIds = blocks.flatMap { block ->
            (block as JsonObject)["rows"]?.let { it as? JsonArray }.orEmpty()
                .mapNotNull { ((it as JsonObject)["vocabEntryId"] as? JsonPrimitive)?.content }
        }.distinct()
        val extraIds = rowEntryIds.filter { it !in keyByEntryId }.map(UUID::fromString)
        val extraEntries = if (extraIds.isEmpty()) emptyList() else
            vocabEntryService.toResponses(VocabEntryTable.selectAll().where { VocabEntryTable.id inList extraIds }.toList())
        extraEntries.forEachIndexed { i, entry -> keyByEntryId[entry.id] = "lib${i + 1}" }

        val topicNames = previous.topics.associate { it.key to it.name }
        val vocab = previous.vocab.map { item ->
            aiVocabJson(item.key, item.entry.lemma, item.entry.article?.name, item.entry.plural, item.entry.wordType.name, item.entry.forms,
                item.entry.government, item.entry.translations, item.entry.explanationDe, item.entry.exampleSentence,
                item.entry.level?.name, item.entry.synonyms, item.topicKey?.let(topicNames::get))
        } + extraEntries.map { entry ->
            aiVocabJson(keyByEntryId.getValue(entry.id), entry.lemma, entry.article?.name, entry.plural, entry.wordType.name, entry.forms,
                entry.government, entry.translations, entry.explanationDe, entry.exampleSentence, entry.level?.name, entry.synonyms, null)
        }
        val pending = previous.vocabTables.associate { it.blockId to it.vocabKeys }
        val output = buildJsonObject {
            put("vocab", JsonArray(vocab))
            put("document", buildJsonObject {
                put("title", document[DocumentTable.title])
                put("blocks", AiBlocks.toAi(blocks, keyByEntryId, pending))
            })
            put("suggestedTopics", JsonArray(previous.topics.map { t ->
                buildJsonObject { put("name", t.name); t.parentName?.let { put("parentName", it) } }
            }))
            put("suggestedGrammarTopics", JsonArray(previous.grammarTopics.map { g ->
                buildJsonObject { put("name", g.name); g.level?.let { put("level", it.name) } }
            }))
            put("correctedSentences", GenerationJobs.json.encodeToJsonElement(previous.correctedSentences))
        }
        val rowEntries = rowEntryIds.mapNotNull { entryId -> keyByEntryId[entryId]?.let { it to entryId } }.toMap()
        return output.toString() to rowEntries
    }

    private fun aiVocabJson(
        key: String, lemma: String, article: String?, plural: String?, wordType: String, forms: String?, government: String?,
        translations: Map<String, String>, explanationDe: String?, exampleSentence: String?, level: String?,
        synonyms: List<String>, topicName: String?,
    ) = buildJsonObject {
        put("key", key); put("lemma", lemma); article?.let { put("article", it) }; plural?.let { put("plural", it) }
        put("wordType", wordType); forms?.let { put("forms", it) }; government?.let { put("government", it) }
        put("translations", JsonObject(translations.mapValues { JsonPrimitive(it.value) }))
        explanationDe?.let { put("explanationDe", it) }; exampleSentence?.let { put("exampleSentence", it) }
        level?.let { put("level", it) }
        put("synonyms", JsonArray(synonyms.map(::JsonPrimitive)))
        topicName?.let { put("topicName", it) }
    }

    /** Matches the output against the teacher's library and the learners' vocab. Must run in a transaction. */
    private fun review(ctx: LessonContext, output: ValidatedOutput): NachbereitungResult {
        val matcher = LibraryMatcher(ctx.teacherId)
        val ai = output.output

        val topics = mutableListOf<TopicProposal>()
        fun topicFor(name: String, parentName: String?): TopicProposal =
            topics.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                ?: TopicProposal("t${topics.size + 1}", name.trim(), parentName?.trim(), matcher.matchTopic(name, parentName)?.toString())
                    .also { topics += it }
        ai.suggestedTopics.forEach { topicFor(it.name, it.parentName) }

        val grammar = mutableListOf<GrammarTopicProposal>()
        ai.suggestedGrammarTopics.forEach { g ->
            if (grammar.none { it.name.equals(g.name.trim(), ignoreCase = true) })
                grammar += GrammarTopicProposal("g${grammar.size + 1}", g.name.trim(), g.level, matcher.matchGrammar(g.name)?.toString())
        }

        val attendees = ctx.attendeeIds
        val vocab = ai.vocab.map { word ->
            val topic = word.topicName?.takeIf { it.isNotBlank() }?.let { topicFor(it, null) }
            val input = word.toInput(listOfNotNull(topic?.existingId), ctx.lessonId.toString())
            val matched = matcher.matchEntry(input.lemma, input.article, input.wordType)
            val matchedId = matched?.get(VocabEntryTable.id)?.value
            val alreadyAssigned = matchedId != null && attendees.isNotEmpty() && StudentVocabTable.selectAll()
                .where { (StudentVocabTable.vocabEntryId eq matchedId) and (StudentVocabTable.studentId inList attendees) }
                .count() == attendees.size.toLong()
            ReviewVocabItem(
                key = word.key,
                entry = input,
                topicKey = topic?.takeIf { it.existingId == null }?.key,
                matchedEntryId = matchedId?.toString(),
                matchedEntry = matched?.let { vocabEntryService.toResponses(listOf(it)).single() },
                alreadyAssigned = alreadyAssigned,
                selected = !alreadyAssigned,
            )
        }

        return NachbereitungResult(
            documentId = "",
            vocab = vocab,
            vocabTables = output.vocabTables.map { (blockId, keys) -> VocabTableRef(blockId, keys) },
            topics = topics,
            grammarTopics = grammar,
            correctedSentences = ai.correctedSentences,
        )
    }

    private fun requireGateway(): LlmGateway {
        return ai.gateway ?: throw ServiceUnavailableException("AI is not configured on this server", code = "AI_NOT_CONFIGURED")
    }

    private fun requireAttendees(ctx: LessonContext) {
        val ok = if (ctx.mode == NachbereitungMode.ONE_ON_ONE) ctx.attendees.size == 1 else ctx.attendees.isNotEmpty()
        if (!ok) throw BadRequestException(
            if (ctx.mode == NachbereitungMode.ONE_ON_ONE) "A 1:1 lesson needs exactly one confirmed student"
            else "The club session has no confirmed participants",
            code = "NACHBEREITUNG_NO_ATTENDEES",
        )
    }

    companion object {
        const val MAX_TOKENS = 16_000

        fun nachbereitungResult(row: ResultRow): NachbereitungResult =
            GenerationJobs.json.decodeFromJsonElement(row[GenerationJobTable.result]!!)
    }
}

package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.DraftBundleTable
import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.DraftItemTable
import com.gvart.parleyroom.ai.data.DraftMode
import com.gvart.parleyroom.ai.data.DraftScope
import com.gvart.parleyroom.ai.data.DraftStatus
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.data.PromptTemplateTable
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.transfer.AttendeeRef
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftBundleSummary
import com.gvart.parleyroom.ai.transfer.DraftContextResponse
import com.gvart.parleyroom.ai.transfer.DraftItemResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.ai.transfer.PatchDraftItemRequest
import com.gvart.parleyroom.ai.transfer.RefineDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftResponse
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import com.gvart.parleyroom.homework.service.HomeworkUnits
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabEntryService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * AI draft bundles: generate (words + homework, or a club's notes document), review items, refine.
 * Everything stays in the draft tables; [DraftSendService] turns approved items into student data.
 */
class DraftBundleService(
    private val ai: AiRuntime,
    private val runner: GenerationJobRunner,
    private val context: LessonContextService,
    private val vocabEntryService: VocabEntryService,
) {

    // ---- Context for the generate form ----

    fun lessonContext(lessonId: UUID, principal: UserPrincipal): DraftContextResponse = transaction {
        val lesson = context.requireLessonTeacher(lessonId, principal)
        val ctx = context.load(lesson)
        DraftContextResponse(
            scope = DraftScope.LESSON,
            mode = ctx.mode,
            aiAvailable = ai.gateway != null,
            lessonId = lessonId.toString(),
            notes = lesson[LessonTable.rawNotes],
            context = ctx.summary(),
            pastLessons = context.pastLessonChoices(ctx, lesson),
            openBundle = openBundle(DraftScope.LESSON, lesson[LessonTable.teacherId].value, lessonId, null)?.let(::summary),
        )
    }

    fun studentContext(studentId: UUID, principal: UserPrincipal): DraftContextResponse = transaction {
        requireStudentOfTeacher(studentId, principal)
        val ctx = context.loadForStudent(principal.id, studentId)
        DraftContextResponse(
            scope = DraftScope.STUDENT,
            mode = DraftMode.ONE_ON_ONE,
            aiAvailable = ai.gateway != null,
            studentId = studentId.toString(),
            context = ctx.summary(),
            pastLessons = context.pastLessonChoices(ctx, null),
            openBundle = openBundle(DraftScope.STUDENT, principal.id, null, studentId)?.let(::summary),
        )
    }

    // ---- Generate ----

    fun generateForLesson(lessonId: UUID, request: GenerateDraftRequest, principal: UserPrincipal): DraftBundleResponse {
        val gateway = GenerationJobs.requireGateway(ai)
        val prepared = transaction {
            LibraryAccess.requireTeacher(principal)
            val lesson = context.requireLessonTeacher(lessonId, principal)
            val ctx = context.load(lesson, sources(principal.id, request))
            requireAttendees(ctx)
            val notes = request.notes ?: lesson[LessonTable.rawNotes].orEmpty()
            Prepared(ctx, notes, instructions(request, principal))
        }
        return start(gateway, DraftScope.LESSON, prepared, request, principal, lessonId = lessonId, studentId = null)
    }

    fun generateForStudent(studentId: UUID, request: GenerateDraftRequest, principal: UserPrincipal): DraftBundleResponse {
        val gateway = GenerationJobs.requireGateway(ai)
        val prepared = transaction {
            LibraryAccess.requireTeacher(principal)
            requireStudentOfTeacher(studentId, principal)
            if (request.kinds == null)
                throw BadRequestException("Say what to generate: kinds WORDS and/or HOMEWORK", code = "AI_DRAFT_KINDS_REQUIRED")
            val ctx = context.loadForStudent(principal.id, studentId, sources(principal.id, request))
            Prepared(ctx, request.notes.orEmpty(), instructions(request, principal))
        }
        return start(gateway, DraftScope.STUDENT, prepared, request, principal, lessonId = null, studentId = studentId)
    }

    private data class Prepared(val ctx: LessonContext, val notes: String, val instructions: String)

    private fun start(
        gateway: LlmGateway, scope: DraftScope, prepared: Prepared, request: GenerateDraftRequest, principal: UserPrincipal,
        lessonId: UUID?, studentId: UUID?,
    ): DraftBundleResponse {
        val (ctx, notes, instructions) = prepared
        // Stored with the effective kinds, so a whole refine regenerates the same parts.
        val kinds = request.kinds?.toSet() ?: DraftKind.entries.toSet()
        @Suppress("NAME_SHADOWING") val request = request.copy(kinds = DraftKind.entries.filter { it in kinds })
        if (notes.isBlank() && ctx.pastNotes.isEmpty() && instructions.isBlank() && ctx.focusTopics.isEmpty() && ctx.focusGrammar.isEmpty())
            throw BadRequestException("Give notes, past lesson notes, a prompt or a focus to generate from", code = "AI_DRAFT_NOTHING_TO_GENERATE")

        // Generating again reuses the open draft: its items are replaced when the job succeeds.
        val (bundleId, created) = transaction {
            val open = openBundle(scope, principal.id, lessonId, studentId)
            if (open != null) {
                requireIdle(open[DraftBundleTable.id].value)
                DraftBundleTable.update({ DraftBundleTable.id eq open[DraftBundleTable.id] }) {
                    it[input] = GenerationJobs.json.encodeToJsonElement(request)
                }
                open[DraftBundleTable.id].value to false
            } else {
                DraftBundleTable.insertAndGetId {
                    it[teacherId] = principal.id
                    it[DraftBundleTable.scope] = scope
                    it[DraftBundleTable.lessonId] = lessonId
                    it[DraftBundleTable.studentId] = studentId
                    it[mode] = ctx.mode
                    it[input] = GenerationJobs.json.encodeToJsonElement(request)
                }.value to true
            }
        }
        val jobInput = JobInput(
            notes = notes.takeIf { it.isNotBlank() }, prompt = request.prompt, promptTemplateId = request.promptTemplateId,
            pastLessonIds = ctx.pastNotes.map { it.ref.id }, topicIds = request.topicIds, grammarTopicIds = request.grammarTopicIds,
            kinds = request.kinds,
        )
        val jobId = try {
            runner.enqueue(principal.id, lessonId, GenerationJobKind.GENERATE, GenerationJobs.json.encodeToJsonElement(jobInput),
                bundleId = bundleId, modelId = gateway.modelId)
        } catch (e: Exception) {
            if (created) transaction { DraftBundleTable.deleteWhere { DraftBundleTable.id eq bundleId } }
            throw e
        }
        runner.launch(jobId) { runGenerate(gateway, bundleId, ctx, notes, instructions, kinds) }
        return get(bundleId, principal)
    }

    private suspend fun runGenerate(
        gateway: LlmGateway, bundleId: UUID, ctx: LessonContext, notes: String, instructions: String, kinds: Set<DraftKind>,
    ): JobSuccess {
        val target = if (ctx.mode == DraftMode.CLUB) DraftTarget.NOTES_DOCUMENT else DraftTarget.BUNDLE
        val request = Prompts.generate(ctx.mode, ctx.toPromptText(), HtmlText.toPlainText(notes), ctx.pastNotesText(), instructions, kinds)
        val completion = GenerationJobs.completeValidated(gateway, Prompts.draftSystem(ctx.mode), request, MAX_TOKENS) {
            AiOutputParser.parseDraft(it, target, kinds)
        }
        return transaction {
            val count = if (lockDraft(bundleId)) {
                val items = DraftItems.fromAi(completion.value, ctx.teacherId, ctx.level, ctx.lessonId, ctx.attendeeIds)
                DraftItemTable.deleteWhere { DraftItemTable.bundleId eq bundleId }
                DraftItems.insert(bundleId, items)
                items.size
            } else 0
            JobSuccess(jobResult(bundleId, count), completion.attempts, completion.usage)
        }
    }

    // ---- Refine ----

    fun refine(bundleId: UUID, request: RefineDraftRequest, principal: UserPrincipal): DraftBundleResponse {
        val gateway = GenerationJobs.requireGateway(ai)
        val (bundle, itemId) = transaction {
            val bundle = requireOwned(bundleId, principal)
            requireDraft(bundle)
            requireIdle(bundleId)
            val itemId = request.itemId?.let { requireItem(bundleId, parseItemId(it))[DraftItemTable.id].value }
            if (itemId == null && itemRows(bundleId).isEmpty())
                throw ConflictException("The draft has nothing to refine yet", code = "AI_DRAFT_EMPTY")
            bundle to itemId
        }
        val kinds = request.kinds?.toSet() ?: storedInput(bundle).kinds?.toSet() ?: DraftKind.entries.toSet()
        val input = JobInput(instruction = request.instruction, itemId = itemId?.toString(), kinds = DraftKind.entries.filter { it in kinds })
        val jobId = runner.enqueue(principal.id, bundle[DraftBundleTable.lessonId]?.value, GenerationJobKind.REFINE,
            GenerationJobs.json.encodeToJsonElement(input), bundleId = bundleId, modelId = gateway.modelId)
        runner.launch(jobId) { runRefine(gateway, bundleId, itemId, request.instruction, kinds) }
        return get(bundleId, principal)
    }

    private suspend fun runRefine(gateway: LlmGateway, bundleId: UUID, itemId: UUID?, instruction: String, kinds: Set<DraftKind>): JobSuccess {
        data class Refine(val ctx: LessonContext, val target: DraftTarget, val current: String, val notes: String, val prompt: String)
        val refine = transaction {
            val bundle = DraftBundleTable.selectAll().where { DraftBundleTable.id eq bundleId }.single()
            val ctx = contextOf(bundle)
            val items = itemRows(bundleId)
            val item = itemId?.let { id -> items.firstOrNull { it[DraftItemTable.id].value == id } }
                ?: if (itemId != null) throw AiJobFailure("AI_DRAFT_ITEM_NOT_FOUND", "The item was deleted") else null
            val target = item?.let { DraftItems.targetOf(it[DraftItemTable.kind]) }
                ?: if (ctx.mode == DraftMode.CLUB) DraftTarget.NOTES_DOCUMENT else DraftTarget.BUNDLE
            val input = storedInput(bundle)
            val notes = input.notes ?: bundle[DraftBundleTable.lessonId]?.let { lessonId ->
                LessonTable.select(LessonTable.rawNotes).where { LessonTable.id eq lessonId }.single()[LessonTable.rawNotes]
            }.orEmpty()
            val prompt = templateText(input.promptTemplateId, ctx.teacherId, input.prompt)
            Refine(ctx, target, DraftItems.toAiJson(listOfNotNull(item).ifEmpty { items }, target, kinds), notes, prompt)
        }
        val request = Prompts.refine(refine.ctx.mode, refine.target, refine.ctx.toPromptText(), HtmlText.toPlainText(refine.notes),
            refine.ctx.pastNotesText(), refine.prompt, refine.current, instruction, kinds)
        val completion = GenerationJobs.completeValidated(gateway, Prompts.draftSystem(refine.ctx.mode), request, MAX_TOKENS) {
            AiOutputParser.parseDraft(it, refine.target, kinds)
        }
        return transaction {
            val ctx = refine.ctx
            val count = if (lockDraft(bundleId)) {
                val items = DraftItems.fromAi(completion.value, ctx.teacherId, ctx.level, ctx.lessonId, ctx.attendeeIds)
                if (itemId == null) {
                    // Only the regenerated kinds are replaced (a club's notes document: everything).
                    val replaced = if (ctx.mode == DraftMode.CLUB) DraftItemKind.entries.toSet() else DraftItems.itemKinds(kinds)
                    DraftItemTable.deleteWhere { (DraftItemTable.bundleId eq bundleId) and (DraftItemTable.kind inList replaced) }
                    DraftItems.insert(bundleId, items, firstPosition = Int.MAX_VALUE / 2)
                    DraftItems.renumber(bundleId)
                } else {
                    // The refined item needs approving again.
                    DraftItemTable.update({ (DraftItemTable.id eq itemId) and (DraftItemTable.bundleId eq bundleId) }) {
                        it[payload] = items.single().payload
                        it[approved] = false
                    }
                }
                items.size
            } else 0
            JobSuccess(jobResult(bundleId, count), completion.attempts, completion.usage)
        }
    }

    // ---- Read ----

    fun get(bundleId: UUID, principal: UserPrincipal): DraftBundleResponse = transaction {
        toResponse(requireReadable(bundleId, principal))
    }

    fun list(principal: UserPrincipal, status: DraftStatus?, lessonId: UUID?, studentId: UUID?): List<DraftBundleSummary> = transaction {
        LibraryAccess.requireTeacher(principal)
        val query = DraftBundleTable.selectAll().where { DraftBundleTable.teacherId eq principal.id }
        status?.let { s -> query.andWhere { DraftBundleTable.status eq s } }
        lessonId?.let { id -> query.andWhere { DraftBundleTable.lessonId eq id } }
        studentId?.let { id -> query.andWhere { DraftBundleTable.studentId eq id } }
        query.orderBy(DraftBundleTable.updatedAt, SortOrder.DESC).limit(MAX_LIST).map(::summary)
    }

    // ---- Items ----

    fun patchItem(bundleId: UUID, itemId: UUID, request: PatchDraftItemRequest, principal: UserPrincipal): DraftItemResponse = transaction {
        val bundle = requireOwned(bundleId, principal)
        requireDraft(bundle)
        requireIdle(bundleId)
        val item = requireItem(bundleId, itemId)
        val kind = item[DraftItemTable.kind]
        val teacherId = bundle[DraftBundleTable.teacherId].value
        fun mismatch(): Nothing = throw BadRequestException("The content does not match the item kind $kind", code = "AI_DRAFT_ITEM_KIND_MISMATCH")

        val payload = when {
            request.word != null -> {
                if (kind != DraftItemKind.WORD) mismatch()
                requireTagIds(teacherId, request.word.topics.mapNotNull { it.id }, request.word.grammarTopics.mapNotNull { it.id })
                DraftItems.payload(DraftItems.match(request.word, LibraryMatcher(teacherId), recipientIds(bundle)))
            }
            request.document != null -> {
                if (kind != DraftItemKind.EXERCISE_DOCUMENT && kind != DraftItemKind.NOTES_DOCUMENT) mismatch()
                requireTagIds(teacherId, request.document.topics.mapNotNull { it.id }, request.document.grammarTopics.mapNotNull { it.id })
                requireDraftBlocks(request.document.blocks, exercise = kind == DraftItemKind.EXERCISE_DOCUMENT)
                DraftItems.payload(request.document.copy(title = request.document.title.trim()))
            }
            request.task != null -> {
                if (kind != DraftItemKind.TASK) mismatch()
                requireTagIds(teacherId, request.task.topics.mapNotNull { it.id }, request.task.grammarTopics.mapNotNull { it.id })
                DraftItems.payload(request.task.copy(title = request.task.title.trim()))
            }
            else -> null
        }
        DraftItemTable.update({ DraftItemTable.id eq itemId }) {
            if (payload != null) it[DraftItemTable.payload] = payload
            request.approved?.let { approved -> it[DraftItemTable.approved] = approved }
        }
        touch(bundleId)
        val row = requireItem(bundleId, itemId)
        DraftItems.toResponse(row, matchedEntries(teacherId, listOf(row)))
    }

    fun deleteItem(bundleId: UUID, itemId: UUID, principal: UserPrincipal) = transaction {
        requireDraft(requireOwned(bundleId, principal))
        requireIdle(bundleId)
        requireItem(bundleId, itemId)
        DraftItemTable.deleteWhere { DraftItemTable.id eq itemId }
        touch(bundleId)
    }

    fun approveAll(bundleId: UUID, principal: UserPrincipal): DraftBundleResponse = transaction {
        requireDraft(requireOwned(bundleId, principal))
        requireIdle(bundleId)
        DraftItemTable.update({ DraftItemTable.bundleId eq bundleId }) { it[approved] = true }
        touch(bundleId)
        toResponse(requireOwned(bundleId, principal))
    }

    /** Discards the draft: nothing is sent, the bundle is kept as DISCARDED. A running job's result is dropped. */
    fun discard(bundleId: UUID, principal: UserPrincipal) = transaction {
        val bundle = requireOwned(bundleId, principal)
        if (bundle[DraftBundleTable.status] == DraftStatus.SENT)
            throw ConflictException("The draft was already sent", code = "AI_DRAFT_NOT_EDITABLE")
        DraftBundleTable.update({ DraftBundleTable.id eq bundleId }) { it[status] = DraftStatus.DISCARDED }
    }

    // ---- Shared helpers (also used by DraftSendService) ----

    fun requireOwned(bundleId: UUID, principal: UserPrincipal): ResultRow {
        val row = requireReadable(bundleId, principal)
        if (row[DraftBundleTable.teacherId].value != principal.id)
            throw NotFoundException("AI draft not found", code = "AI_DRAFT_NOT_FOUND")
        return row
    }

    fun requireIdle(bundleId: UUID) {
        val active = GenerationJobTable.selectAll()
            .where {
                (GenerationJobTable.bundleId eq bundleId) and
                        (GenerationJobTable.status inList listOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING))
            }
            .empty().not()
        if (active) throw ConflictException("An AI job is still working on this draft", code = "AI_DRAFT_BUSY")
    }

    fun itemRows(bundleId: UUID): List<ResultRow> = DraftItemTable.selectAll()
        .where { DraftItemTable.bundleId eq bundleId }
        .orderBy(DraftItemTable.position)
        .toList()

    /** Who Send targets by default: the lesson's confirmed attendees, or the bundle's student. */
    fun recipients(bundle: ResultRow): List<AttendeeRef> {
        val lessonId = bundle[DraftBundleTable.lessonId]?.value
        val query = if (lessonId != null) (LessonStudentTable innerJoin UserTable)
            .select(UserTable.id, UserTable.firstName, UserTable.lastName)
            .where { (LessonStudentTable.lessonId eq lessonId) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) }
        else UserTable.select(UserTable.id, UserTable.firstName, UserTable.lastName)
            .where { UserTable.id eq bundle[DraftBundleTable.studentId]!! }
        return query.orderBy(UserTable.lastName).map { AttendeeRef(it[UserTable.id].value.toString(), it[UserTable.firstName], it[UserTable.lastName]) }
    }

    fun toResponse(bundle: ResultRow): DraftBundleResponse {
        val bundleId = bundle[DraftBundleTable.id].value
        val items = itemRows(bundleId)
        val matched = matchedEntries(bundle[DraftBundleTable.teacherId].value, items)
        return DraftBundleResponse(
            id = bundleId.toString(),
            scope = bundle[DraftBundleTable.scope],
            mode = bundle[DraftBundleTable.mode],
            status = bundle[DraftBundleTable.status],
            lessonId = bundle[DraftBundleTable.lessonId]?.value?.toString(),
            studentId = bundle[DraftBundleTable.studentId]?.value?.toString(),
            input = storedInput(bundle),
            job = latestJob(bundleId)?.let(GenerationJobs::toResponse),
            items = items.map { DraftItems.toResponse(it, matched) },
            approvedCount = items.count { it[DraftItemTable.approved] },
            recipients = recipients(bundle),
            sendResult = bundle[DraftBundleTable.sendResult]?.let { GenerationJobs.json.decodeFromJsonElement<SendDraftResponse>(it) },
            createdAt = bundle[DraftBundleTable.createdAt],
            updatedAt = bundle[DraftBundleTable.updatedAt],
            sentAt = bundle[DraftBundleTable.sentAt],
        )
    }

    private fun summary(bundle: ResultRow): DraftBundleSummary {
        val bundleId = bundle[DraftBundleTable.id].value
        val count = DraftItemTable.id.count()
        val counts = DraftItemTable.select(DraftItemTable.approved, count)
            .where { DraftItemTable.bundleId eq bundleId }
            .groupBy(DraftItemTable.approved)
            .associate { it[DraftItemTable.approved] to it[count].toInt() }
        val lesson = bundle[DraftBundleTable.lessonId]?.let { LessonTable.findByIdOrThrow(it.value, "Lesson") }
        val student = bundle[DraftBundleTable.studentId]?.let { id ->
            UserTable.selectAll().where { UserTable.id eq id }.singleOrNull()
                ?.let { AttendeeRef(it[UserTable.id].value.toString(), it[UserTable.firstName], it[UserTable.lastName]) }
        }
        return DraftBundleSummary(
            id = bundleId.toString(),
            scope = bundle[DraftBundleTable.scope],
            mode = bundle[DraftBundleTable.mode],
            status = bundle[DraftBundleTable.status],
            lessonId = lesson?.get(LessonTable.id)?.value?.toString(),
            lessonTitle = lesson?.get(LessonTable.title),
            lessonScheduledAt = lesson?.get(LessonTable.scheduledAt),
            student = student,
            itemCount = counts.values.sum(),
            approvedCount = counts[true] ?: 0,
            job = latestJob(bundleId)?.let(GenerationJobs::toResponse),
            createdAt = bundle[DraftBundleTable.createdAt],
            updatedAt = bundle[DraftBundleTable.updatedAt],
            sentAt = bundle[DraftBundleTable.sentAt],
        )
    }

    private fun requireReadable(bundleId: UUID, principal: UserPrincipal): ResultRow {
        val row = DraftBundleTable.selectAll().where { DraftBundleTable.id eq bundleId }.singleOrNull()
        if (row == null || (principal.role != UserRole.ADMIN && row[DraftBundleTable.teacherId].value != principal.id))
            throw NotFoundException("AI draft not found", code = "AI_DRAFT_NOT_FOUND")
        return row
    }

    private fun requireDraft(bundle: ResultRow) {
        if (bundle[DraftBundleTable.status] != DraftStatus.DRAFT)
            throw ConflictException("The draft was already ${bundle[DraftBundleTable.status].name.lowercase()}", code = "AI_DRAFT_NOT_EDITABLE")
    }

    private fun requireItem(bundleId: UUID, itemId: UUID): ResultRow = DraftItemTable.selectAll()
        .where { (DraftItemTable.id eq itemId) and (DraftItemTable.bundleId eq bundleId) }
        .singleOrNull() ?: throw NotFoundException("Draft item not found", code = "AI_DRAFT_ITEM_NOT_FOUND")

    private fun parseItemId(raw: String): UUID = runCatching { UUID.fromString(raw) }
        .getOrElse { throw NotFoundException("Draft item not found", code = "AI_DRAFT_ITEM_NOT_FOUND") }

    private fun requireStudentOfTeacher(studentId: UUID, principal: UserPrincipal) {
        LibraryAccess.requireTeacher(principal)
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
    }

    private fun requireAttendees(ctx: LessonContext) {
        val ok = if (ctx.mode == DraftMode.ONE_ON_ONE) ctx.attendees.size == 1 else ctx.attendees.isNotEmpty()
        if (!ok) throw BadRequestException(
            if (ctx.mode == DraftMode.ONE_ON_ONE) "A 1:1 lesson needs exactly one confirmed student"
            else "The club session has no confirmed participants",
            code = "NACHBEREITUNG_NO_ATTENDEES",
        )
    }

    private fun requireTagIds(teacherId: UUID, topicIds: List<String>, grammarTopicIds: List<String>) {
        LibraryAccess.requireTopics(teacherId, topicIds)
        LibraryAccess.requireGrammarTopics(teacherId, grammarTopicIds)
    }

    /** Draft documents must pass the block schema, have no vocab tables, and (homework) stay answerable in the app. */
    private fun requireDraftBlocks(blocks: kotlinx.serialization.json.JsonArray, exercise: Boolean) {
        DocumentBlockValidator.validate(blocks)
        blocks.forEachIndexed { i, block ->
            if (((block as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "vocab_table")
                throw BadRequestException("vocab_table is not allowed in a draft document: approve words instead",
                    code = "AI_DRAFT_DOCUMENT_INVALID", pointer = "/document/blocks/$i")
        }
        if (exercise && HomeworkUnits.documentUnits(UUID.randomUUID(), blocks).isEmpty())
            throw BadRequestException("The homework document needs at least one interactive exercise", code = "AI_DRAFT_DOCUMENT_INVALID")
    }

    private fun sources(teacherId: UUID, request: GenerateDraftRequest) = ContextSources(
        pastLessonIds = request.pastLessonIds?.map { parse(it, "pastLessonIds") },
        topicIds = LibraryAccess.requireTopics(teacherId, request.topicIds),
        grammarTopicIds = LibraryAccess.requireGrammarTopics(teacherId, request.grammarTopicIds),
    )

    /** The template's text (if any) before the teacher's prompt. */
    private fun instructions(request: GenerateDraftRequest, principal: UserPrincipal): String {
        request.promptTemplateId?.let { raw ->
            val id = parse(raw, "promptTemplateId")
            PromptTemplateTable.selectAll().where { (PromptTemplateTable.id eq id) and (PromptTemplateTable.teacherId eq principal.id) }
                .singleOrNull() ?: throw NotFoundException("Prompt template not found", code = "PROMPT_TEMPLATE_NOT_FOUND")
        }
        return templateText(request.promptTemplateId, principal.id, request.prompt)
    }

    private fun templateText(templateId: String?, teacherId: UUID, prompt: String): String {
        val template = templateId?.let { raw ->
            PromptTemplateTable.select(PromptTemplateTable.text)
                .where { (PromptTemplateTable.id eq UUID.fromString(raw)) and (PromptTemplateTable.teacherId eq teacherId) }
                .singleOrNull()?.get(PromptTemplateTable.text)
        }
        return listOfNotNull(template, prompt.takeIf { it.isNotBlank() }).joinToString("\n\n")
    }

    private fun contextOf(bundle: ResultRow): LessonContext {
        val input = storedInput(bundle)
        val teacherId = bundle[DraftBundleTable.teacherId].value
        val sources = ContextSources(
            pastLessonIds = input.pastLessonIds?.map(UUID::fromString),
            topicIds = input.topicIds.map(UUID::fromString),
            grammarTopicIds = input.grammarTopicIds.map(UUID::fromString),
        )
        return bundle[DraftBundleTable.lessonId]?.let { context.load(LessonTable.findByIdOrThrow(it.value, "Lesson"), sources) }
            ?: context.loadForStudent(teacherId, bundle[DraftBundleTable.studentId]!!.value, sources)
    }

    private fun storedInput(bundle: ResultRow): GenerateDraftRequest =
        GenerationJobs.json.decodeFromJsonElement(bundle[DraftBundleTable.input])

    private fun recipientIds(bundle: ResultRow) = recipients(bundle).map { UUID.fromString(it.id) }

    private fun openBundle(scope: DraftScope, teacherId: UUID, lessonId: UUID?, studentId: UUID?): ResultRow? {
        val query = DraftBundleTable.selectAll().where { (DraftBundleTable.scope eq scope) and (DraftBundleTable.status eq DraftStatus.DRAFT) }
        if (lessonId != null) query.andWhere { DraftBundleTable.lessonId eq lessonId }
        else query.andWhere { (DraftBundleTable.teacherId eq teacherId) and (DraftBundleTable.studentId eq studentId!!) }
        return query.singleOrNull()
    }

    /** Locks the bundle row; false when it is no longer a draft (sent / discarded meanwhile). */
    private fun lockDraft(bundleId: UUID): Boolean = DraftBundleTable.selectAll()
        .where { DraftBundleTable.id eq bundleId }
        .forUpdate()
        .singleOrNull()?.get(DraftBundleTable.status) == DraftStatus.DRAFT

    private fun latestJob(bundleId: UUID): ResultRow? = GenerationJobTable.selectAll()
        .where { GenerationJobTable.bundleId eq bundleId }
        .orderBy(GenerationJobTable.createdAt, SortOrder.DESC)
        .limit(1)
        .singleOrNull()

    private fun matchedEntries(teacherId: UUID, items: List<ResultRow>): Map<String, com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse> {
        val ids = items.filter { it[DraftItemTable.kind] == DraftItemKind.WORD }
            .mapNotNull { DraftItems.word(it).libraryEntryId?.let(UUID::fromString) }
        if (ids.isEmpty()) return emptyMap()
        val rows = VocabEntryTable.selectAll().where { (VocabEntryTable.id inList ids) and (VocabEntryTable.teacherId eq teacherId) }.toList()
        return vocabEntryService.toResponses(rows).associateBy { it.id }
    }

    private fun touch(bundleId: UUID) {
        // updated_at is set by the trigger; the no-op write fires it.
        DraftBundleTable.update({ DraftBundleTable.id eq bundleId }) { it[status] = DraftStatus.DRAFT }
    }

    private fun jobResult(bundleId: UUID, itemCount: Int) = buildJsonObject {
        put("bundleId", bundleId.toString())
        put("itemCount", itemCount)
    }

    private fun parse(raw: String, field: String): UUID = runCatching { UUID.fromString(raw) }
        .getOrElse { throw BadRequestException("$field contains an invalid uuid", code = "VALIDATION_FAILED") }

    companion object {
        const val MAX_TOKENS = 16_000
        const val MAX_LIST = 100
    }
}

package com.gvart.parleyroom.lessonprep.service

import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.data.AiUseCase
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.service.AiCall
import com.gvart.parleyroom.ai.service.AiJobFailure
import com.gvart.parleyroom.ai.service.AiOutputParser
import com.gvart.parleyroom.ai.service.GenerationJobRunner
import com.gvart.parleyroom.ai.service.GenerationJobs
import com.gvart.parleyroom.ai.service.JobSuccess
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.RoadmapMilestoneTable
import com.gvart.parleyroom.goal.service.GoalViews
import com.gvart.parleyroom.goal.service.NameMask
import com.gvart.parleyroom.goal.service.RoadmapService
import com.gvart.parleyroom.goal.transfer.BatchItemStatesRequest
import com.gvart.parleyroom.goal.transfer.MilestoneView
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lessonprep.data.LessonPlanStatus
import com.gvart.parleyroom.lessonprep.data.LessonPlanTable
import com.gvart.parleyroom.lessonprep.transfer.CreateLessonPlanRequest
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanPatch
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanResponse
import com.gvart.parleyroom.lessonprep.transfer.NextLessonPrepResponse
import com.gvart.parleyroom.lessonprep.transfer.RegenerateLessonPlanRequest
import com.gvart.parleyroom.lessonprep.transfer.WrapUpRequest
import com.gvart.parleyroom.lessonprep.transfer.WrapUpResponse
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/** The result of a SUCCEEDED LESSON_PREP job; the plan itself is read from the lesson plan. */
@Serializable
data class LessonPrepJobResult(val lessonPlanId: String)

/**
 * "Prepare next lesson": an AI-drafted lesson plan per student lesson (LESSON_PREP jobs), teacher edits
 * and the wrap-up after the lesson. Only the plan's teacher or an admin; students never see plans.
 */
class LessonPrepService(
    private val ai: AiRuntime,
    private val runner: GenerationJobRunner,
    private val roadmap: RoadmapService,
) {

    fun create(request: CreateLessonPlanRequest, principal: UserPrincipal): LessonPlanResponse {
        GenerationJobs.requireGateway(ai)
        val studentId = UUID.fromString(request.studentId)
        val planId = transaction {
            LessonPrepAccess.requireStudent(studentId, principal)
            val teacher = LessonPrepAccess.teacherScope(principal)
            val lesson = request.lessonId?.let { raw ->
                LessonPrepAccess.requireLesson(UUID.fromString(raw), studentId, teacher, "/lessonId")
                    .also { requireUnplanned(it[LessonTable.id].value, null) }
            } ?: LessonPrepAccess.nextLesson(studentId, teacher, unplannedOnly = true)
            val now = OffsetDateTime.now()
            LessonPlanTable.insertAndGetId {
                it[LessonPlanTable.studentId] = studentId
                it[teacherId] = lesson?.get(LessonTable.teacherId)?.value ?: principal.id
                it[goalId] = activeGoal(studentId, teacher)
                it[lessonId] = lesson?.get(LessonTable.id)?.value
                it[prepNote] = request.prepNote!!.trim()
                it[explainInRussian] = request.explainInRussian
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        }
        return generate(planId, principal)
    }

    fun get(planId: UUID, principal: UserPrincipal): LessonPlanResponse = transaction {
        LessonPlanViews.plan(LessonPrepAccess.requirePlan(planId, principal))
    }

    /** The plan linked to [lessonId], with the same access as [get]. */
    fun getByLesson(lessonId: UUID, principal: UserPrincipal): LessonPlanResponse = transaction {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Lesson plans are for teachers only")
        val planId = LessonPlanTable.select(LessonPlanTable.id).where { LessonPlanTable.lessonId eq lessonId }
            .firstOrNull()?.get(LessonPlanTable.id)?.value
            ?: throw NotFoundException("Lesson plan not found", code = "LESSON_PLAN_NOT_FOUND")
        LessonPlanViews.plan(LessonPrepAccess.requirePlan(planId, principal))
    }

    /** Keeps the lesson link; a new prep note replaces the stored one. Not for a DONE plan. */
    fun regenerate(planId: UUID, request: RegenerateLessonPlanRequest, principal: UserPrincipal): LessonPlanResponse {
        GenerationJobs.requireGateway(ai)
        transaction {
            val plan = LessonPrepAccess.requirePlan(planId, principal)
            if (plan[LessonPlanTable.status] == LessonPlanStatus.DONE)
                throw ConflictException("The lesson was wrapped up; its plan is history", code = "LESSON_PLAN_DONE")
            LessonPlanTable.update({ LessonPlanTable.id eq planId }) {
                request.prepNote?.let { note -> it[prepNote] = note.trim() }
                request.explainInRussian?.let { explain -> it[explainInRussian] = explain }
                it[updatedAt] = OffsetDateTime.now()
            }
        }
        return generate(planId, principal)
    }

    fun patch(planId: UUID, patch: LessonPlanPatch, principal: UserPrincipal): LessonPlanResponse = transaction {
        val plan = LessonPrepAccess.requirePlan(planId, principal)
        val studentId = plan[LessonPlanTable.studentId].value
        val lessonId = patch.lessonId?.takeIf { it.isNotEmpty() }?.let { raw ->
            val teacher = LessonPrepAccess.teacherScope(principal) ?: plan[LessonPlanTable.teacherId].value
            LessonPrepAccess.requireLesson(UUID.fromString(raw), studentId, teacher, "/lessonId")[LessonTable.id].value
                .also { requireUnplanned(it, planId) }
        }
        patch.plan?.let { requireGoalItems(plan[LessonPlanTable.goalId]?.value, it.roadmapItemIds) }
        LessonPlanTable.update({ LessonPlanTable.id eq planId }) {
            patch.prepNote?.let { note -> it[prepNote] = note.trim() }
            patch.coveredNote?.let { note -> it[coveredNote] = note.trim().ifEmpty { null } }
            if (patch.lessonId != null) it[LessonPlanTable.lessonId] = lessonId
            patch.status?.let { status -> it[LessonPlanTable.status] = LessonPlanStatus.valueOf(status) }
            patch.plan?.let { content -> it[LessonPlanTable.plan] = content }
            it[updatedAt] = OffsetDateTime.now()
        }
        LessonPlanViews.plan(LessonPrepAccess.findPlan(planId)!!)
    }

    fun delete(planId: UUID, principal: UserPrincipal) = transaction {
        LessonPrepAccess.requirePlan(planId, principal)
        LessonPlanTable.deleteWhere { LessonPlanTable.id eq planId }
    }

    /**
     * After the lesson: saves what was covered, marks the plan DONE and applies the roadmap item states
     * with the lesson (RoadmapService.batchStates, same transaction). A 1:1 lesson without a plan gets
     * one (no AI plan) so the covered note lands in the history. Idempotent.
     */
    fun wrapUp(lessonId: UUID, request: WrapUpRequest, principal: UserPrincipal): WrapUpResponse = transaction {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Only the teacher wraps up a lesson")
        val lesson = LessonTable.selectAll().where { LessonTable.id eq lessonId }.singleOrNull()
        if (lesson == null || (principal.role != UserRole.ADMIN && lesson[LessonTable.teacherId]?.value != principal.id))
            throw NotFoundException("Lesson not found", code = "LESSON_NOT_FOUND")
        val plan = LessonPlanTable.selectAll().where { LessonPlanTable.lessonId eq lessonId }.singleOrNull()
            ?: planForLesson(lesson)
        val planId = plan[LessonPlanTable.id].value
        LessonPlanTable.update({ LessonPlanTable.id eq planId }) {
            request.coveredNote?.let { note -> it[coveredNote] = note.trim().ifEmpty { null } }
            it[status] = LessonPlanStatus.DONE
            it[updatedAt] = OffsetDateTime.now()
        }
        if (request.itemStates.isNotEmpty()) {
            val teacher = lesson[LessonTable.teacherId]?.value
            val goalId = plan[LessonPlanTable.goalId]?.value ?: activeGoal(plan[LessonPlanTable.studentId].value, teacher)
                ?: throw BadRequestException("The student has no active goal of this teacher", "LESSON_PLAN_NO_GOAL", "/itemStates")
            roadmap.batchStates(goalId, BatchItemStatesRequest(lessonId.toString(), request.itemStates), principal)
        }
        val view = LessonPlanViews.plan(LessonPrepAccess.findPlan(planId)!!)
        WrapUpResponse(view, view.plan?.suggestedHomework)
    }

    /** The next upcoming CONFIRMED lesson with the student and its plan, if any. */
    fun nextLessonPrep(studentId: UUID, principal: UserPrincipal): NextLessonPrepResponse = transaction {
        LessonPrepAccess.requireStudent(studentId, principal)
        val lesson = LessonPrepAccess.nextLesson(studentId, LessonPrepAccess.teacherScope(principal), unplannedOnly = false)
            ?: return@transaction NextLessonPrepResponse()
        val plan = LessonPlanTable.selectAll().where { LessonPlanTable.lessonId eq lesson[LessonTable.id] }.singleOrNull()
        NextLessonPrepResponse(LessonPlanViews.lessonRef(lesson), plan?.let(LessonPlanViews::plan))
    }

    // ---- Generation ----

    private fun generate(planId: UUID, principal: UserPrincipal): LessonPlanResponse {
        val prompt = transaction {
            val plan = LessonPrepAccess.findPlan(planId)!!
            requireIdle(planId)
            LessonPrepPrompt.build(plan)
        }
        val model = ai.models.modelFor(AiUseCase.LESSON_PREP)
        val input = JobInput(notes = prompt.prepNote, explainInRussian = prompt.explainInRussian)
        val jobId = runner.enqueue(
            principal.id, prompt.lessonId, GenerationJobKind.LESSON_PREP, GenerationJobs.json.encodeToJsonElement(input),
            lessonPlanId = planId, modelId = model,
        )
        runner.launch(jobId) {
            val call = AiCall(principal.id, AiUseCase.LESSON_PREP, model, jobId)
            val completion = GenerationJobs.completeValidated(ai, call, Prompts.lessonPrepSystem, prompt.text, MAX_TOKENS) {
                AiOutputParser.parseLessonPlan(it, prompt.minutes, prompt.itemKeys.keys)
            }
            val content = completion.value.copy(roadmapItemIds = completion.value.roadmapItemIds.map { prompt.itemKeys.getValue(it).toString() })
            transaction {
                val current = LessonPrepAccess.findPlan(planId)
                    ?: throw AiJobFailure("LESSON_PLAN_NOT_FOUND", "The lesson plan was deleted while the job was running")
                if (current[LessonPlanTable.status] == LessonPlanStatus.DONE)
                    throw AiJobFailure("LESSON_PLAN_DONE", "The lesson was wrapped up while the job was running")
                LessonPlanTable.update({ LessonPlanTable.id eq planId }) {
                    it[plan] = content
                    it[status] = LessonPlanStatus.DRAFT
                    it[updatedAt] = OffsetDateTime.now()
                }
            }
            JobSuccess(GenerationJobs.json.encodeToJsonElement(LessonPrepJobResult(planId.toString())), completion.attempts, completion.usage)
        }
        return transaction { LessonPlanViews.plan(LessonPrepAccess.findPlan(planId)!!) }
    }

    private fun requireIdle(planId: UUID) {
        val busy = GenerationJobTable.selectAll().where {
            (GenerationJobTable.lessonPlanId eq planId) and
                    (GenerationJobTable.status inList listOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING))
        }.empty().not()
        if (busy) throw ConflictException("A preparation of this lesson plan is still running", code = "LESSON_PLAN_BUSY")
    }

    // ---- Helpers ----

    /** One plan per lesson. */
    private fun requireUnplanned(lessonId: UUID, except: UUID?) {
        val other = LessonPlanTable.select(LessonPlanTable.id).where { LessonPlanTable.lessonId eq lessonId }
            .map { it[LessonPlanTable.id].value }.firstOrNull { it != except }
        if (other != null)
            throw ConflictException("The lesson already has a plan ($other)", code = "LESSON_PLAN_EXISTS", existingId = other.toString())
    }

    /** The student's ACTIVE goal (of [teacher] when given). */
    private fun activeGoal(studentId: UUID, teacher: UUID?): UUID? = GoalTable.select(GoalTable.id)
        .where {
            (GoalTable.studentId eq studentId) and (GoalTable.status eq GoalStatus.ACTIVE) and
                    (if (teacher != null) GoalTable.teacherId eq teacher else Op.TRUE)
        }
        .singleOrNull()?.get(GoalTable.id)?.value

    /** Teacher-edited roadmapItemIds must be items of the plan's goal. */
    private fun requireGoalItems(goalId: UUID?, ids: List<String>) {
        if (ids.isEmpty()) return
        val known = if (goalId == null) emptySet() else (RoadmapItemTable innerJoin RoadmapMilestoneTable)
            .select(RoadmapItemTable.id)
            .where { (RoadmapItemTable.id inList ids.map(UUID::fromString)) and (RoadmapMilestoneTable.goalId eq goalId) }
            .map { it[RoadmapItemTable.id].value.toString() }.toSet()
        ids.indexOfFirst { it.lowercase() !in known }.takeIf { it >= 0 }?.let {
            throw BadRequestException("roadmapItemIds must be items of the plan's goal", "LESSON_PLAN_ITEM_INVALID", "/plan/roadmapItemIds/$it")
        }
    }

    /** A plan without AI content for a lesson with exactly one student. */
    private fun planForLesson(lesson: ResultRow): ResultRow {
        val lessonId = lesson[LessonTable.id].value
        val students = LessonStudentTable.select(LessonStudentTable.studentId)
            .where { (LessonStudentTable.lessonId eq lessonId) and (LessonStudentTable.status neq LessonStudentStatus.REJECTED) }
            .map { it[LessonStudentTable.studentId].value }
        val studentId = students.singleOrNull()
            ?: throw NotFoundException("A group lesson can only be wrapped up through a plan", code = "LESSON_PLAN_NOT_FOUND")
        val teacher = lesson[LessonTable.teacherId]?.value
            ?: throw NotFoundException("Lesson not found", code = "LESSON_NOT_FOUND")
        val now = OffsetDateTime.now()
        val id = LessonPlanTable.insertAndGetId {
            it[LessonPlanTable.studentId] = studentId
            it[teacherId] = teacher
            it[goalId] = activeGoal(studentId, teacher)
            it[LessonPlanTable.lessonId] = lessonId
            it[prepNote] = ""
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        return LessonPrepAccess.findPlan(id)!!
    }

    companion object {
        /** The JSON plan is about 1.5–3k tokens; Sonnet thinking counts against this too. */
        const val MAX_TOKENS = 8_000
    }
}

/** Who may see what. Must run in a transaction. */
object LessonPrepAccess {

    /** Lessons are filtered by teacher, except for admins (null). */
    fun teacherScope(principal: UserPrincipal): UUID? = principal.id.takeIf { principal.role != UserRole.ADMIN }

    fun findPlan(planId: UUID): ResultRow? = LessonPlanTable.selectAll().where { LessonPlanTable.id eq planId }.singleOrNull()

    /** The plan's teacher or an admin; students 403, other teachers 404. */
    fun requirePlan(planId: UUID, principal: UserPrincipal): ResultRow {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Lesson plans are for teachers only")
        val plan = findPlan(planId)
        if (plan == null || (principal.role != UserRole.ADMIN && plan[LessonPlanTable.teacherId].value != principal.id))
            throw NotFoundException("Lesson plan not found", code = "LESSON_PLAN_NOT_FOUND")
        return plan
    }

    /** A linked teacher or an admin; students 403, other teachers 404 (ids cannot be probed). */
    fun requireStudent(studentId: UUID, principal: UserPrincipal) {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Lesson plans are for teachers only")
        val exists = if (principal.role == UserRole.ADMIN)
            UserTable.select(UserTable.id).where { (UserTable.id eq studentId) and (UserTable.role eq UserRole.STUDENT) }.empty().not()
        else
            TeacherStudentTable.selectAll()
                .where { (TeacherStudentTable.teacherId eq principal.id) and (TeacherStudentTable.studentId eq studentId) }
                .empty().not()
        if (!exists) throw NotFoundException("Student not found", code = "STUDENT_NOT_FOUND")
    }

    /** Lessons the student takes part in (not rejected, not a pending request) of [teacher] (any when null). */
    fun studentLessons(studentId: UUID, teacher: UUID?): Op<Boolean> =
        (LessonStudentTable.studentId eq studentId) and (LessonStudentTable.status neq LessonStudentStatus.REJECTED) and
                (LessonTable.status notInList listOf(LessonStatus.REQUEST)) and
                (if (teacher != null) LessonTable.teacherId eq teacher else Op.TRUE)

    fun requireLesson(lessonId: UUID, studentId: UUID, teacher: UUID?, pointer: String): ResultRow =
        (LessonTable innerJoin LessonStudentTable).select(LessonTable.columns)
            .where { (LessonTable.id eq lessonId) and studentLessons(studentId, teacher) }
            .singleOrNull()
            ?: throw BadRequestException("lessonId is not a lesson of this teacher with the student", "LESSON_PLAN_LESSON_INVALID", pointer)

    /** Lessons wrapped up (their plan is DONE), whenever they were scheduled. */
    private fun wrappedUp() = LessonPlanTable.select(LessonPlanTable.lessonId)
        .where { (LessonPlanTable.status eq LessonPlanStatus.DONE) and LessonPlanTable.lessonId.isNotNull() }

    /** Took place: scheduled before [now], or taught early (IN_PROGRESS, COMPLETED or wrapped up). */
    fun tookPlace(now: OffsetDateTime): Op<Boolean> =
        (LessonTable.scheduledAt lessEq now) or
                (LessonTable.status inList listOf(LessonStatus.IN_PROGRESS, LessonStatus.COMPLETED)) or
                (LessonTable.id inSubQuery wrappedUp())

    /** The earliest upcoming CONFIRMED lesson not wrapped up yet ([unplannedOnly]: without a plan). */
    fun nextLesson(studentId: UUID, teacher: UUID?, unplannedOnly: Boolean): ResultRow? {
        val planned = if (!unplannedOnly) emptyList() else LessonPlanTable.select(LessonPlanTable.lessonId)
            .where { LessonPlanTable.studentId eq studentId }.mapNotNull { it[LessonPlanTable.lessonId]?.value }
        return (LessonTable innerJoin LessonStudentTable).select(LessonTable.columns)
            .where {
                studentLessons(studentId, teacher) and (LessonTable.status eq LessonStatus.CONFIRMED) and
                        (LessonTable.scheduledAt greater OffsetDateTime.now()) and
                        (LessonTable.id notInSubQuery wrappedUp()) and
                        (if (planned.isEmpty()) Op.TRUE else LessonTable.id notInList planned)
            }
            .orderBy(LessonTable.scheduledAt, SortOrder.ASC)
            .limit(1)
            .singleOrNull()
    }

    /** The last [limit] lessons before [before], newest first, cancelled ones left out. */
    fun pastLessons(studentId: UUID, teacher: UUID?, before: OffsetDateTime, limit: Int): List<ResultRow> =
        (LessonTable innerJoin LessonStudentTable).select(LessonTable.columns)
            .where {
                studentLessons(studentId, teacher) and (LessonTable.status neq LessonStatus.CANCELLED) and
                        (LessonTable.scheduledAt less before)
            }
            .orderBy(LessonTable.scheduledAt, SortOrder.DESC)
            .limit(limit)
            .toList()
}

/** The LESSON_PREP user message and what validating the answer needs. */
private data class LessonPrepPrompt(
    val text: String,
    val minutes: Int,
    /** Prompt key ("I1") → roadmap item. */
    val itemKeys: Map<String, UUID>,
    val lessonId: UUID?,
    val prepNote: String,
    val explainInRussian: Boolean,
) {
    companion object {
        private const val PAST_LESSONS = 3
        private const val MAX_RAW_NOTES = 1_500
        private const val MAX_COVERED = 800
        private const val MAX_COMMENT = 300
        private const val MAX_DONE_ITEMS = 40
        private const val DEFAULT_MINUTES = 60

        /** Only what the plan needs: no names (masked in free text), emails or other personal data. Must run in a transaction. */
        fun build(plan: ResultRow): LessonPrepPrompt {
            val studentId = plan[LessonPlanTable.studentId].value
            val mask = NameMask.forStudent(studentId)
            val lesson = plan[LessonPlanTable.lessonId]?.value?.let { id -> LessonTable.selectAll().where { LessonTable.id eq id }.singleOrNull() }
            val goal: TeacherGoalView? = plan[LessonPlanTable.goalId]?.value?.let { id ->
                if (GoalTable.selectAll().where { GoalTable.id eq id }.empty()) null else GoalViews.teacher(id)
            }
            val minutes = lesson?.get(LessonTable.durationMinutes) ?: goal?.lessonMinutes ?: DEFAULT_MINUTES
            val level = UserTable.select(UserTable.level).where { UserTable.id eq studentId }.singleOrNull()?.get(UserTable.level)
            val explain = plan[LessonPlanTable.explainInRussian]

            // The current milestone is the first one not done; its items and the next one's can be targeted.
            val current = goal?.milestones?.indexOfFirst { it.doneAt == null }?.takeIf { it >= 0 }
            val targets = current?.let { goal.milestones.drop(it).take(2) }.orEmpty()
            val itemKeys = linkedMapOf<String, UUID>()
            val roadmap = targets.mapIndexed { i, m -> milestoneText(if (i == 0) "Current" else "Next", m, mask, itemKeys) }
                .joinToString("\n\n").ifEmpty { null }
            val targetIds = targets.flatMap { m -> m.items.map { it.id } }.toSet()
            val doneItems = goal?.milestones.orEmpty().flatMap { it.items }
                .filter { it.state == RoadmapItemState.DONE && it.id !in targetIds }
                .take(MAX_DONE_ITEMS).joinToString("\n") { "- [${it.kind}] ${it.title}" }
            val hints = goal?.openMismatches.orEmpty().joinToString("\n") { m ->
                "- Milestone \"${m.milestoneTitle}\": the student answered ${m.answer}" +
                        (m.comment?.let { " — \"${mask.mask(it)!!.take(MAX_COMMENT)}\"" } ?: "")
            }

            val teacher = plan[LessonPlanTable.teacherId].value
            val before = lesson?.get(LessonTable.scheduledAt) ?: OffsetDateTime.now()
            val past = LessonPrepAccess.pastLessons(studentId, teacher, before, PAST_LESSONS)
            val pastPlans = if (past.isEmpty()) emptyMap() else LessonPlanTable.selectAll()
                .where { LessonPlanTable.lessonId inList past.map { it[LessonTable.id].value } }
                .associateBy { it[LessonPlanTable.lessonId]!!.value }
            val pastText = past.joinToString("\n\n") { row ->
                val pastPlan = pastPlans[row[LessonTable.id].value]
                buildString {
                    append("Lesson on ${row[LessonTable.scheduledAt].toLocalDate()} (${row[LessonTable.durationMinutes]} min, ${row[LessonTable.status]})")
                    row[LessonTable.rawNotes]?.takeIf { it.isNotBlank() }?.let { append("\nNotes: ${mask.mask(it)!!.take(MAX_RAW_NOTES)}") }
                    pastPlan?.get(LessonPlanTable.plan)?.lessonGoal?.let { append("\nPlanned goal: ${mask.mask(it)}") }
                    pastPlan?.get(LessonPlanTable.coveredNote)?.let { append("\nCovered: ${mask.mask(it)!!.take(MAX_COVERED)}") }
                }
            }

            val goalText = goal?.let {
                buildString {
                    appendLine("Title: ${mask.mask(it.title)}")
                    appendLine("Exam: ${it.exam ?: "(none)"}")
                    appendLine("Target level: ${it.targetLevel ?: "(not set)"}")
                    append("Deadline: ${it.deadline}")
                }
            }
            val lessonText = buildString {
                appendLine("Minutes: $minutes")
                appendLine("Student level: ${level ?: "(unknown)"}")
                append("Date: ${lesson?.get(LessonTable.scheduledAt)?.toLocalDate() ?: "(not scheduled yet)"}")
            }
            val language = if (explain)
                "Teacher-facing text in German; add a short Russian explanation in brackets where it helps the teacher (e.g. grammar)."
            else "All text in German."
            val prepNote = plan[LessonPlanTable.prepNote]
            val text = Prompts.lessonPrep(
                lesson = lessonText,
                prepNote = mask.mask(prepNote).orEmpty(),
                goal = goalText,
                roadmap = roadmap,
                doneItems = doneItems,
                hints = hints,
                pastLessons = pastText,
                interests = mask.mask(goal?.interests),
                examReference = goal?.let { Prompts.examReference(it.exam, it.targetLevel) },
                language = language,
            )
            return LessonPrepPrompt(text, minutes, itemKeys, plan[LessonPlanTable.lessonId]?.value, prepNote, explain)
        }

        private fun milestoneText(label: String, m: MilestoneView, mask: NameMask, keys: MutableMap<String, UUID>): String = buildString {
            appendLine("$label milestone: ${m.title} (${m.startDate}..${m.endDate})")
            appendLine("Topic: ${m.topic ?: "-"}")
            appendLine("Grammar: ${m.grammarPoints.joinToString().ifEmpty { "-" }}")
            appendLine("Word fields: ${m.wordFields.joinToString().ifEmpty { "-" }}")
            appendLine("Exam part: ${m.examPart ?: "-"}")
            appendLine("Teacher tips: ${mask.mask(m.teacherTips) ?: "-"}")
            append("Items:")
            if (m.items.isEmpty()) append(" (none)")
            m.items.forEach { item ->
                val key = "I${keys.size + 1}"
                keys[key] = UUID.fromString(item.id)
                append("\n$key [${item.kind}] ${item.title} — ${item.state}")
            }
        }
    }
}

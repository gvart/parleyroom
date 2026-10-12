package com.gvart.parleyroom.goal.service

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
import com.gvart.parleyroom.ai.service.RoadmapNotes
import com.gvart.parleyroom.ai.service.ValidatedMilestone
import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.ai.transfer.JobInput
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.RoadmapMilestoneTable
import com.gvart.parleyroom.goal.transfer.GenerateRoadmapRequest
import com.gvart.parleyroom.goal.transfer.RegenerateMilestoneRequest
import com.gvart.parleyroom.goal.transfer.RoadmapJobResult
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * AI drafts of a goal's roadmap (ROADMAP) and of one milestone (MILESTONE_REGEN) as generation jobs.
 * The model sees only the goal's fields and the student's CEFR level; the student's name is masked
 * in the teacher's free text. The roadmap is written when the job succeeds.
 */
class RoadmapGenerationService(private val ai: AiRuntime, private val runner: GenerationJobRunner) {

    /** Only when the goal has no roadmap, or with [GenerateRoadmapRequest.replace]. */
    fun generate(goalId: UUID, request: GenerateRoadmapRequest, principal: UserPrincipal): GenerationJobResponse {
        GenerationJobs.requireGateway(ai)
        val prompt = transaction {
            val goal = GoalAccess.requireTeacher(goalId, principal)
            requireIdle(goalId)
            if (!request.replace && hasRoadmap(goalId))
                throw ConflictException("The goal already has a roadmap; pass replace=true to replace it", code = "ROADMAP_EXISTS")
            val (start, deadline) = goal[GoalTable.startDate].toJavaLocalDate() to goal[GoalTable.deadline].toJavaLocalDate()
            Prompts.roadmap(goalText(goal), schedule(goal), notes(goal), examReference(goal), mask(goal, request.instruction)) to (start to deadline)
        }
        val (text, period) = prompt
        val input = JobInput(instruction = request.instruction, replace = request.replace)
        return start(goalId, GenerationJobKind.ROADMAP, AiUseCase.ROADMAP, input, principal) { call ->
            val completion = GenerationJobs.completeValidated(ai, call, Prompts.roadmapSystem, text, ROADMAP_MAX_TOKENS) {
                AiOutputParser.parseRoadmap(it, period.first, period.second)
            }
            val items = transaction { writeRoadmap(goalId, completion.value, request.replace) }
            val result = RoadmapJobResult(goalId.toString(), completion.value.size, items)
            JobSuccess(GenerationJobs.json.encodeToJsonElement(result), completion.attempts, completion.usage)
        }
    }

    /** Rewrites one milestone that is not done: keeps its position, dates, weight; replaces its items. */
    fun regenerate(goalId: UUID, milestoneId: UUID, request: RegenerateMilestoneRequest, principal: UserPrincipal): GenerationJobResponse {
        GenerationJobs.requireGateway(ai)
        val text = transaction {
            val goal = GoalAccess.requireTeacher(goalId, principal)
            val milestone = GoalAccess.milestone(goalId, milestoneId)
            if (milestone[RoadmapMilestoneTable.doneAt] != null)
                throw ConflictException("The milestone is done; mark it undone first", code = "ROADMAP_MILESTONE_DONE")
            requireIdle(goalId)
            Prompts.milestoneRegen(goalText(goal), roadmapText(goalId, milestoneId), notes(goal), examReference(goal), mask(goal, request.instruction))
        }
        val input = JobInput(instruction = request.instruction, milestoneId = milestoneId.toString())
        return start(goalId, GenerationJobKind.MILESTONE_REGEN, AiUseCase.MILESTONE_REGEN, input, principal) { call ->
            val completion = GenerationJobs.completeValidated(ai, call, Prompts.milestoneRegenSystem, text, MILESTONE_MAX_TOKENS) {
                AiOutputParser.parseMilestoneRegen(it)
            }
            val items = transaction { writeMilestone(milestoneId, completion.value) }
            val result = RoadmapJobResult(goalId.toString(), 1, items, milestoneId.toString())
            JobSuccess(GenerationJobs.json.encodeToJsonElement(result), completion.attempts, completion.usage)
        }
    }

    private fun start(
        goalId: UUID, kind: GenerationJobKind, useCase: AiUseCase, input: JobInput, principal: UserPrincipal,
        work: suspend (AiCall) -> JobSuccess,
    ): GenerationJobResponse {
        val model = ai.models.modelFor(useCase)
        val jobId = runner.enqueue(principal.id, null, kind, GenerationJobs.json.encodeToJsonElement(input), goalId = goalId, modelId = model)
        runner.launch(jobId) { work(AiCall(principal.id, useCase, model, jobId)) }
        return transaction { GenerationJobs.toResponse(GenerationJobs.requireReadable(jobId, principal)) }
    }

    private fun requireIdle(goalId: UUID) {
        val busy = GenerationJobTable.selectAll().where {
            (GenerationJobTable.goalId eq goalId) and
                    (GenerationJobTable.status inList listOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING))
        }.empty().not()
        if (busy) throw ConflictException("A roadmap job of this goal is still running", code = "ROADMAP_BUSY")
    }

    private fun hasRoadmap(goalId: UUID) = RoadmapMilestoneTable.selectAll().where { RoadmapMilestoneTable.goalId eq goalId }.empty().not()

    /** Returns the number of items written. */
    private fun writeRoadmap(goalId: UUID, milestones: List<ValidatedMilestone>, replace: Boolean): Int {
        if (GoalTable.selectAll().where { GoalTable.id eq goalId }.empty())
            throw AiJobFailure("GOAL_NOT_FOUND", "The goal was deleted while the job was running")
        // The teacher may have added milestones by hand meanwhile: never overwrite them silently.
        if (!replace && hasRoadmap(goalId))
            throw AiJobFailure("ROADMAP_EXISTS", "The goal got a roadmap while the job was running")
        RoadmapMilestoneTable.deleteWhere { RoadmapMilestoneTable.goalId eq goalId }
        val now = OffsetDateTime.now()
        return milestones.mapIndexed { i, milestone ->
            val id = RoadmapMilestoneTable.insertAndGetId {
                it[RoadmapMilestoneTable.goalId] = goalId
                it[position] = i + 1
                it[startDate] = milestone.startDate!!.toKotlinLocalDate()
                it[endDate] = milestone.endDate!!.toKotlinLocalDate()
                it[weight] = BigDecimal.valueOf(milestone.weight)
                content(it, milestone)
                it[createdAt] = now
                it[updatedAt] = now
            }.value
            insertItems(id, milestone, now)
        }.sum()
    }

    private fun writeMilestone(milestoneId: UUID, milestone: ValidatedMilestone): Int {
        val now = OffsetDateTime.now()
        val updated = RoadmapMilestoneTable.update({ RoadmapMilestoneTable.id eq milestoneId }) {
            content(it, milestone)
            it[updatedAt] = now
        }
        if (updated == 0) throw AiJobFailure("ROADMAP_MILESTONE_NOT_FOUND", "The milestone was deleted while the job was running")
        RoadmapItemTable.deleteWhere { RoadmapItemTable.milestoneId eq milestoneId }
        return insertItems(milestoneId, milestone, now)
    }

    private fun content(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, milestone: ValidatedMilestone) {
        it[RoadmapMilestoneTable.title] = milestone.title
        it[RoadmapMilestoneTable.topic] = milestone.topic
        it[RoadmapMilestoneTable.grammarPoints] = milestone.grammarPoints
        it[RoadmapMilestoneTable.wordFields] = milestone.wordFields
        it[RoadmapMilestoneTable.examPart] = milestone.examPart
        it[RoadmapMilestoneTable.teacherTips] = milestone.teacherTips
        it[RoadmapMilestoneTable.canDo] = milestone.canDo
    }

    private fun insertItems(milestoneId: UUID, milestone: ValidatedMilestone, now: OffsetDateTime): Int {
        RoadmapItemTable.batchInsert(milestone.items.withIndex()) { (i, item) ->
            this[RoadmapItemTable.milestoneId] = milestoneId
            this[RoadmapItemTable.position] = i + 1
            this[RoadmapItemTable.kind] = item.first
            this[RoadmapItemTable.title] = item.second
            this[RoadmapItemTable.createdAt] = now
            this[RoadmapItemTable.updatedAt] = now
        }
        return milestone.items.size
    }

    // ---- Prompt input: the goal's fields only ----

    private fun goalText(goal: ResultRow): String {
        val level = UserTable.select(UserTable.level).where { UserTable.id eq goal[GoalTable.studentId] }.singleOrNull()?.get(UserTable.level)
        return buildString {
            appendLine("Title: ${mask(goal, goal[GoalTable.title])}")
            appendLine("Exam: ${goal[GoalTable.exam] ?: "(none)"}")
            appendLine("Target level: ${goal[GoalTable.targetLevel] ?: "(not set)"}")
            appendLine("Current level: ${level ?: "(unknown)"}")
            appendLine("Start date: ${goal[GoalTable.startDate]}")
            appendLine("Deadline: ${goal[GoalTable.deadline]}")
            appendLine("Lessons per week: ${goal[GoalTable.lessonsPerWeek].stripTrailingZeros().toPlainString()}")
            append("Minutes per lesson: ${goal[GoalTable.lessonMinutes]}")
        }
    }

    /** Weeks and lessons until the deadline; about one milestone per [LESSONS_PER_MILESTONE] lessons. */
    private fun schedule(goal: ResultRow): String {
        val start = goal[GoalTable.startDate].toJavaLocalDate()
        val deadline = goal[GoalTable.deadline].toJavaLocalDate()
        val days = ChronoUnit.DAYS.between(start, deadline) + 1
        val weeks = days / 7.0
        val lessons = (weeks * goal[GoalTable.lessonsPerWeek].toDouble()).roundToInt().coerceAtLeast(1)
        val milestones = ceil(lessons / LESSONS_PER_MILESTONE).toInt().coerceIn(1, minOf(MAX_PLANNED_MILESTONES.toLong(), days).toInt())
        return buildString {
            appendLine("Days: $days")
            appendLine("Weeks: ${"%.1f".format(java.util.Locale.ROOT, weeks)}")
            appendLine("Lessons: about $lessons")
            append("Milestones: $milestones")
        }
    }

    private fun notes(goal: ResultRow) = RoadmapNotes(
        levelNote = mask(goal, goal[GoalTable.levelNote]),
        interests = mask(goal, goal[GoalTable.interests]),
        materials = mask(goal, goal[GoalTable.materials]),
    )

    private fun examReference(goal: ResultRow): String? = Prompts.examReference(goal[GoalTable.exam], goal[GoalTable.targetLevel])

    /** Every milestone in order with its content; [rewrite] is marked. */
    private fun roadmapText(goalId: UUID, rewrite: UUID): String = RoadmapMilestoneTable.selectAll()
        .where { RoadmapMilestoneTable.goalId eq goalId }
        .orderBy(RoadmapMilestoneTable.position, SortOrder.ASC)
        .joinToString("\n") { m ->
            val mark = if (m[RoadmapMilestoneTable.id].value == rewrite) "[REWRITE] " else ""
            "$mark${m[RoadmapMilestoneTable.position]}. ${m[RoadmapMilestoneTable.title]} " +
                    "(${m[RoadmapMilestoneTable.startDate]}..${m[RoadmapMilestoneTable.endDate]}); " +
                    "topic: ${m[RoadmapMilestoneTable.topic] ?: "-"}; grammar: ${m[RoadmapMilestoneTable.grammarPoints].joinToString()}; " +
                    "words: ${m[RoadmapMilestoneTable.wordFields].joinToString()}; exam part: ${m[RoadmapMilestoneTable.examPart] ?: "-"}"
        }

    /** The teacher's free text with the student's first and last name replaced by "[Name]". */
    private fun mask(goal: ResultRow, text: String?): String? {
        if (text.isNullOrBlank()) return text
        val user = UserTable.select(UserTable.firstName, UserTable.lastName).where { UserTable.id eq goal[GoalTable.studentId] }.singleOrNull()
            ?: return text
        return listOf(user[UserTable.firstName], user[UserTable.lastName])
            .filter { it.trim().length >= 2 }
            .fold(text) { acc, name -> acc.replace(Regex("\\b${Regex.escape(name.trim())}\\b", RegexOption.IGNORE_CASE), "[Name]") }
    }

    companion object {
        const val ROADMAP_MAX_TOKENS = 16_000
        const val MILESTONE_MAX_TOKENS = 4_000
        const val LESSONS_PER_MILESTONE = 4.0
        const val MAX_PLANNED_MILESTONES = 12
    }
}

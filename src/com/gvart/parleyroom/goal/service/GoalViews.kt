package com.gvart.parleyroom.goal.service

import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.service.GenerationJobs
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.MilestoneSelfAssessmentTable
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.RoadmapMilestoneTable
import com.gvart.parleyroom.goal.data.SelfAssessmentAnswer
import com.gvart.parleyroom.goal.transfer.GoalProgressView
import com.gvart.parleyroom.goal.transfer.GoalSummary
import com.gvart.parleyroom.goal.transfer.MilestoneView
import com.gvart.parleyroom.goal.transfer.OpenMismatch
import com.gvart.parleyroom.goal.transfer.PendingSelfAssessment
import com.gvart.parleyroom.goal.transfer.RoadmapItemView
import com.gvart.parleyroom.goal.transfer.RoadmapJobRef
import com.gvart.parleyroom.goal.transfer.SelfAssessmentView
import com.gvart.parleyroom.goal.transfer.StudentGoalView
import com.gvart.parleyroom.goal.transfer.StudentMilestone
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.roundToInt

/** Who may touch a goal. Must run in a transaction. */
object GoalAccess {

    fun find(goalId: UUID): ResultRow =
        GoalTable.selectAll().where { GoalTable.id eq goalId }.singleOrNull()
            ?: throw NotFoundException("Goal not found", code = "GOAL_NOT_FOUND")

    /** The goal's teacher or an admin. Students use /goals/my (403); other teachers get 404 so ids cannot be probed. */
    fun requireTeacher(goalId: UUID, principal: UserPrincipal): ResultRow {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Students see their goal at /api/v1/goals/my")
        val row = find(goalId)
        if (principal.role != UserRole.ADMIN && row[GoalTable.teacherId]?.value != principal.id)
            throw NotFoundException("Goal not found", code = "GOAL_NOT_FOUND")
        return row
    }

    fun milestone(goalId: UUID, milestoneId: UUID): ResultRow =
        RoadmapMilestoneTable.selectAll()
            .where { (RoadmapMilestoneTable.id eq milestoneId) and (RoadmapMilestoneTable.goalId eq goalId) }
            .singleOrNull() ?: throw NotFoundException("Milestone not found", code = "ROADMAP_MILESTONE_NOT_FOUND")

    /** The item with its milestone, if it belongs to [goalId]. */
    fun item(goalId: UUID, itemId: UUID): ResultRow =
        (RoadmapItemTable innerJoin RoadmapMilestoneTable).selectAll()
            .where { (RoadmapItemTable.id eq itemId) and (RoadmapMilestoneTable.goalId eq goalId) }
            .singleOrNull() ?: throw NotFoundException("Roadmap item not found", code = "ROADMAP_ITEM_NOT_FOUND")

    fun studentZone(studentId: UUID): ZoneId = zones(listOf(studentId))[studentId] ?: ZoneOffset.UTC

    fun zones(studentIds: List<UUID>): Map<UUID, ZoneId> =
        if (studentIds.isEmpty()) emptyMap()
        else UserTable.select(UserTable.id, UserTable.timezone).where { UserTable.id inList studentIds.distinct() }
            .associate { it[UserTable.id].value to (runCatching { ZoneId.of(it[UserTable.timezone]) }.getOrNull() ?: ZoneOffset.UTC) }
}

/** Builds the goal responses from the roadmap rows. Must run in a transaction. */
object GoalViews {

    private class Roadmap(
        val milestones: List<ResultRow>,
        val items: Map<UUID, List<ResultRow>>,
        val assessments: Map<UUID, ResultRow>,
    ) {
        fun counts(milestone: ResultRow): MilestoneCounts {
            val items = items[milestone[RoadmapMilestoneTable.id].value].orEmpty()
            return MilestoneCounts(
                milestone[RoadmapMilestoneTable.weight].toDouble(),
                items.size,
                items.count { it[RoadmapItemTable.state] == RoadmapItemState.DONE },
            )
        }
    }

    fun teacher(goalId: UUID): TeacherGoalView {
        val goal = GoalAccess.find(goalId)
        val roadmap = load(listOf(goal)).getValue(goalId)
        val milestones = roadmap.milestones.map { milestone ->
            val id = milestone[RoadmapMilestoneTable.id].value
            val done = milestone[RoadmapMilestoneTable.doneAt] != null
            MilestoneView(
                id = id.toString(),
                position = milestone[RoadmapMilestoneTable.position],
                title = milestone[RoadmapMilestoneTable.title],
                startDate = milestone[RoadmapMilestoneTable.startDate].toString(),
                endDate = milestone[RoadmapMilestoneTable.endDate].toString(),
                weight = milestone[RoadmapMilestoneTable.weight].toDouble(),
                topic = milestone[RoadmapMilestoneTable.topic],
                grammarPoints = milestone[RoadmapMilestoneTable.grammarPoints],
                wordFields = milestone[RoadmapMilestoneTable.wordFields],
                examPart = milestone[RoadmapMilestoneTable.examPart],
                teacherTips = milestone[RoadmapMilestoneTable.teacherTips],
                canDo = milestone[RoadmapMilestoneTable.canDo],
                doneAt = milestone[RoadmapMilestoneTable.doneAt],
                progressPercent = GoalProgress.milestone(roadmap.counts(milestone))?.roundToInt(),
                items = roadmap.items[id].orEmpty().map { item ->
                    RoadmapItemView(
                        id = item[RoadmapItemTable.id].value.toString(),
                        position = item[RoadmapItemTable.position],
                        kind = item[RoadmapItemTable.kind],
                        title = item[RoadmapItemTable.title],
                        state = item[RoadmapItemTable.state],
                        stateChangedAt = item[RoadmapItemTable.stateChangedAt],
                        lessonId = item[RoadmapItemTable.lessonId]?.value?.toString(),
                    )
                },
                selfAssessment = roadmap.assessments[id]?.let { answer ->
                    SelfAssessmentView(
                        answer = answer[MilestoneSelfAssessmentTable.answer],
                        comment = answer[MilestoneSelfAssessmentTable.comment],
                        createdAt = answer[MilestoneSelfAssessmentTable.createdAt],
                        mismatch = done && answer[MilestoneSelfAssessmentTable.answer] != SelfAssessmentAnswer.YES,
                    )
                },
            )
        }
        return TeacherGoalView(
            id = goalId.toString(),
            studentId = goal[GoalTable.studentId].value.toString(),
            teacherId = goal[GoalTable.teacherId]?.value?.toString(),
            title = goal[GoalTable.title],
            exam = goal[GoalTable.exam],
            targetLevel = goal[GoalTable.targetLevel],
            startDate = goal[GoalTable.startDate].toString(),
            deadline = goal[GoalTable.deadline].toString(),
            lessonsPerWeek = goal[GoalTable.lessonsPerWeek].toDouble(),
            lessonMinutes = goal[GoalTable.lessonMinutes],
            levelNote = goal[GoalTable.levelNote],
            interests = goal[GoalTable.interests],
            materials = goal[GoalTable.materials],
            status = goal[GoalTable.status],
            progress = progress(goal, roadmap),
            milestones = milestones,
            openMismatches = milestones.mapNotNull { m ->
                m.selfAssessment?.takeIf { it.mismatch }?.let { OpenMismatch(m.id, m.title, it.answer, it.comment, it.createdAt) }
            },
            activeJob = activeJob(goalId),
            createdAt = goal[GoalTable.createdAt],
            updatedAt = goal[GoalTable.updatedAt],
        )
    }

    fun summaries(goals: List<ResultRow>): List<GoalSummary> {
        val roadmaps = load(goals)
        val zones = GoalAccess.zones(goals.map { it[GoalTable.studentId].value })
        return goals.map { goal ->
            val id = goal[GoalTable.id].value
            GoalSummary(
                id = id.toString(),
                studentId = goal[GoalTable.studentId].value.toString(),
                teacherId = goal[GoalTable.teacherId]?.value?.toString(),
                title = goal[GoalTable.title],
                exam = goal[GoalTable.exam],
                targetLevel = goal[GoalTable.targetLevel],
                startDate = goal[GoalTable.startDate].toString(),
                deadline = goal[GoalTable.deadline].toString(),
                status = goal[GoalTable.status],
                progress = progress(goal, roadmaps.getValue(id), zones[goal[GoalTable.studentId].value]),
                createdAt = goal[GoalTable.createdAt],
                updatedAt = goal[GoalTable.updatedAt],
            )
        }
    }

    /**
     * What a student may see of their goal: no items, grammar, word fields, tips, exam part or the
     * teacher's input. The current milestone is the first one not marked done.
     */
    fun student(goal: ResultRow): StudentGoalView {
        val roadmap = load(listOf(goal)).getValue(goal[GoalTable.id].value)
        val milestones = roadmap.milestones
        val currentIndex = milestones.indexOfFirst { it[RoadmapMilestoneTable.doneAt] == null }.takeIf { it >= 0 }
        val progress = progress(goal, roadmap)
        val pending = milestones.firstOrNull {
            it[RoadmapMilestoneTable.doneAt] != null && it[RoadmapMilestoneTable.id].value !in roadmap.assessments
        }
        return StudentGoalView(
            id = goal[GoalTable.id].value.toString(),
            title = goal[GoalTable.title],
            deadline = goal[GoalTable.deadline].toString(),
            progressPercent = progress.percent,
            status = progress.status,
            milestones = milestones.mapIndexed { i, m ->
                StudentMilestone(m[RoadmapMilestoneTable.id].value.toString(), m[RoadmapMilestoneTable.position],
                    done = m[RoadmapMilestoneTable.doneAt] != null, current = i == currentIndex)
            },
            currentCanDo = currentIndex?.let { milestones[it][RoadmapMilestoneTable.canDo] },
            nextCanDo = currentIndex?.let { milestones.getOrNull(it + 1)?.get(RoadmapMilestoneTable.canDo) },
            coveredTitles = milestones.filter { it[RoadmapMilestoneTable.doneAt] != null }.map { it[RoadmapMilestoneTable.title] },
            pendingSelfAssessment = pending?.let {
                PendingSelfAssessment(it[RoadmapMilestoneTable.id].value.toString(), it[RoadmapMilestoneTable.canDo])
            },
        )
    }

    private fun progress(goal: ResultRow, roadmap: Roadmap, zone: ZoneId? = null): GoalProgressView {
        val today = LocalDate.now(zone ?: GoalAccess.studentZone(goal[GoalTable.studentId].value))
        val percent = GoalProgress.progress(roadmap.milestones.map(roadmap::counts))
        val elapsed = GoalProgress.elapsed(goal[GoalTable.startDate].toJavaLocalDate(), goal[GoalTable.deadline].toJavaLocalDate(), today)
        return GoalProgressView(percent?.roundToInt(), elapsed.roundToInt(), GoalProgress.status(percent, elapsed))
    }

    /** Milestones, items and the goal's student's self-assessments of [goals], in three queries. */
    private fun load(goals: List<ResultRow>): Map<UUID, Roadmap> {
        val goalIds = goals.map { it[GoalTable.id].value }
        val milestones = if (goalIds.isEmpty()) emptyList() else RoadmapMilestoneTable.selectAll()
            .where { RoadmapMilestoneTable.goalId inList goalIds }
            .orderBy(RoadmapMilestoneTable.position to SortOrder.ASC, RoadmapMilestoneTable.createdAt to SortOrder.ASC)
            .toList()
        val milestoneIds = milestones.map { it[RoadmapMilestoneTable.id].value }
        val items = if (milestoneIds.isEmpty()) emptyMap() else RoadmapItemTable.selectAll()
            .where { RoadmapItemTable.milestoneId inList milestoneIds }
            .orderBy(RoadmapItemTable.position to SortOrder.ASC, RoadmapItemTable.createdAt to SortOrder.ASC)
            .groupBy { it[RoadmapItemTable.milestoneId].value }
        val studentOf = goals.associate { it[GoalTable.id].value to it[GoalTable.studentId].value }
        val goalOf = milestones.associate { it[RoadmapMilestoneTable.id].value to it[RoadmapMilestoneTable.goalId].value }
        val assessments = if (milestoneIds.isEmpty()) emptyMap() else MilestoneSelfAssessmentTable.selectAll()
            .where { MilestoneSelfAssessmentTable.milestoneId inList milestoneIds }
            .filter { it[MilestoneSelfAssessmentTable.studentId].value == studentOf[goalOf[it[MilestoneSelfAssessmentTable.milestoneId].value]] }
            .associateBy { it[MilestoneSelfAssessmentTable.milestoneId].value }
        val byGoal = milestones.groupBy { it[RoadmapMilestoneTable.goalId].value }
        return goalIds.associateWith { Roadmap(byGoal[it].orEmpty(), items, assessments) }
    }

    private fun activeJob(goalId: UUID): RoadmapJobRef? = GenerationJobTable.selectAll()
        .where {
            (GenerationJobTable.goalId eq goalId) and
                    (GenerationJobTable.status inList listOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING))
        }
        .orderBy(GenerationJobTable.createdAt, SortOrder.DESC)
        .firstOrNull()
        ?.let { job ->
            RoadmapJobRef(
                id = job[GenerationJobTable.id].value.toString(),
                kind = job[GenerationJobTable.kind],
                status = job[GenerationJobTable.status],
                milestoneId = GenerationJobs.input(job).milestoneId.takeIf { job[GenerationJobTable.kind] == GenerationJobKind.MILESTONE_REGEN },
            )
        }
}

package com.gvart.parleyroom.goal.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.MilestoneSelfAssessmentTable
import com.gvart.parleyroom.goal.data.RoadmapItemKind
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.RoadmapMilestoneTable
import com.gvart.parleyroom.goal.data.SelfAssessmentAnswer
import com.gvart.parleyroom.goal.transfer.BatchItemStatesRequest
import com.gvart.parleyroom.goal.transfer.ItemInput
import com.gvart.parleyroom.goal.transfer.ItemPatch
import com.gvart.parleyroom.goal.transfer.MilestoneInput
import com.gvart.parleyroom.goal.transfer.MilestonePatch
import com.gvart.parleyroom.goal.transfer.MoveItemRequest
import com.gvart.parleyroom.goal.transfer.MyGoalResponse
import com.gvart.parleyroom.goal.transfer.ReorderRequest
import com.gvart.parleyroom.goal.transfer.SelfAssessmentInput
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Teacher edits of a goal's roadmap and the student's self-assessment. Every teacher write answers
 * with the full [TeacherGoalView]. Positions are 1-based and kept contiguous.
 */
class RoadmapService(private val notifications: NotificationService) {

    // ---- Milestones ----

    fun createMilestone(goalId: UUID, input: MilestoneInput, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) {
        val count = milestoneCount(goalId)
        val position = (input.position ?: (count + 1)).coerceAtMost(count + 1)
        RoadmapMilestoneTable.update({ (RoadmapMilestoneTable.goalId eq goalId) and (RoadmapMilestoneTable.position greaterEq position) }) {
            it[RoadmapMilestoneTable.position] = RoadmapMilestoneTable.position + 1
        }
        val now = OffsetDateTime.now()
        RoadmapMilestoneTable.insert {
            it[RoadmapMilestoneTable.goalId] = goalId
            it[RoadmapMilestoneTable.position] = position
            it[title] = input.title!!.trim()
            it[startDate] = LocalDate.parse(input.startDate).toKotlinLocalDate()
            it[endDate] = LocalDate.parse(input.endDate).toKotlinLocalDate()
            it[weight] = BigDecimal.valueOf(input.weight ?: 1.0)
            it[topic] = clean(input.topic)
            it[grammarPoints] = input.grammarPoints.orEmpty().map(String::trim)
            it[wordFields] = input.wordFields.orEmpty().map(String::trim)
            it[examPart] = clean(input.examPart)
            it[teacherTips] = clean(input.teacherTips)
            it[canDo] = input.canDo!!.trim()
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    fun patchMilestone(goalId: UUID, milestoneId: UUID, patch: MilestonePatch, principal: UserPrincipal): TeacherGoalView =
        write(goalId, principal) {
            val row = GoalAccess.milestone(goalId, milestoneId)
            val start = patch.startDate?.let(LocalDate::parse) ?: row[RoadmapMilestoneTable.startDate].toJavaLocalDate()
            val end = patch.endDate?.let(LocalDate::parse) ?: row[RoadmapMilestoneTable.endDate].toJavaLocalDate()
            if (end < start)
                throw BadRequestException("endDate must not be before startDate ($start)", "GOAL_INVALID", if (patch.endDate != null) "/endDate" else "/startDate")
            RoadmapMilestoneTable.update({ RoadmapMilestoneTable.id eq milestoneId }) {
                patch.title?.let { value -> it[title] = value.trim() }
                patch.startDate?.let { _ -> it[startDate] = start.toKotlinLocalDate() }
                patch.endDate?.let { _ -> it[endDate] = end.toKotlinLocalDate() }
                patch.weight?.let { value -> it[weight] = BigDecimal.valueOf(value) }
                patch.topic?.let { value -> it[topic] = clean(value) }
                patch.grammarPoints?.let { value -> it[grammarPoints] = value.map(String::trim) }
                patch.wordFields?.let { value -> it[wordFields] = value.map(String::trim) }
                patch.examPart?.let { value -> it[examPart] = clean(value) }
                patch.teacherTips?.let { value -> it[teacherTips] = clean(value) }
                patch.canDo?.let { value -> it[canDo] = value.trim() }
                it[updatedAt] = OffsetDateTime.now()
            }
        }

    fun deleteMilestone(goalId: UUID, milestoneId: UUID, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) {
        val row = GoalAccess.milestone(goalId, milestoneId)
        RoadmapMilestoneTable.deleteWhere { RoadmapMilestoneTable.id eq milestoneId }
        RoadmapMilestoneTable.update({
            (RoadmapMilestoneTable.goalId eq goalId) and (RoadmapMilestoneTable.position greater row[RoadmapMilestoneTable.position])
        }) { it[position] = position - 1 }
    }

    fun reorderMilestones(goalId: UUID, request: ReorderRequest, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) {
        val current = RoadmapMilestoneTable.select(RoadmapMilestoneTable.id).where { RoadmapMilestoneTable.goalId eq goalId }
            .map { it[RoadmapMilestoneTable.id].value }
        applyOrder(request, current) { id, position ->
            RoadmapMilestoneTable.update({ RoadmapMilestoneTable.id eq id }) { it[RoadmapMilestoneTable.position] = position }
        }
    }

    /** Marks the milestone done and asks the student to self-assess (in-app notification). Repeating it changes nothing. */
    fun markDone(goalId: UUID, milestoneId: UUID, principal: UserPrincipal): TeacherGoalView {
        var notifyStudent: UUID? = null
        val view = write(goalId, principal) { goal ->
            val row = GoalAccess.milestone(goalId, milestoneId)
            if (row[RoadmapMilestoneTable.doneAt] != null) return@write
            RoadmapMilestoneTable.update({ RoadmapMilestoneTable.id eq milestoneId }) {
                it[doneAt] = OffsetDateTime.now()
                it[updatedAt] = OffsetDateTime.now()
            }
            notifyStudent = goal[GoalTable.studentId].value
        }
        notifyStudent?.let { notifications.createNotification(it, principal.id, NotificationType.MILESTONE_COMPLETED, goalId) }
        return view
    }

    fun markUndone(goalId: UUID, milestoneId: UUID, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) {
        GoalAccess.milestone(goalId, milestoneId)
        RoadmapMilestoneTable.update({ RoadmapMilestoneTable.id eq milestoneId }) {
            it[doneAt] = null
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    // ---- Items ----

    fun createItem(goalId: UUID, milestoneId: UUID, input: ItemInput, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) {
        GoalAccess.milestone(goalId, milestoneId)
        val position = insertPosition(milestoneId, input.position)
        val now = OffsetDateTime.now()
        RoadmapItemTable.insert {
            it[RoadmapItemTable.milestoneId] = milestoneId
            it[RoadmapItemTable.position] = position
            it[kind] = RoadmapItemKind.valueOf(input.kind!!)
            it[title] = input.title!!.trim()
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    fun patchItem(goalId: UUID, itemId: UUID, patch: ItemPatch, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) { goal ->
        val item = GoalAccess.item(goalId, itemId)
        val lessonId = patch.lessonId?.let(UUID::fromString)?.also { requireLesson(goal, it, "/lessonId") }
        RoadmapItemTable.update({ RoadmapItemTable.id eq itemId }) {
            patch.kind?.let { value -> it[kind] = RoadmapItemKind.valueOf(value) }
            patch.title?.let { value -> it[title] = value.trim() }
            it[updatedAt] = OffsetDateTime.now()
        }
        if (patch.state != null) setState(item, RoadmapItemState.valueOf(patch.state), lessonId)
        else if (lessonId != null) RoadmapItemTable.update({ RoadmapItemTable.id eq itemId }) { it[RoadmapItemTable.lessonId] = lessonId }
    }

    fun deleteItem(goalId: UUID, itemId: UUID, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) {
        val item = GoalAccess.item(goalId, itemId)
        RoadmapItemTable.deleteWhere { RoadmapItemTable.id eq itemId }
        closeGap(item[RoadmapItemTable.milestoneId].value, item[RoadmapItemTable.position])
    }

    fun reorderItems(goalId: UUID, milestoneId: UUID, request: ReorderRequest, principal: UserPrincipal): TeacherGoalView =
        write(goalId, principal) {
            GoalAccess.milestone(goalId, milestoneId)
            val current = RoadmapItemTable.select(RoadmapItemTable.id).where { RoadmapItemTable.milestoneId eq milestoneId }
                .map { it[RoadmapItemTable.id].value }
            applyOrder(request, current) { id, position ->
                RoadmapItemTable.update({ RoadmapItemTable.id eq id }) { it[RoadmapItemTable.position] = position }
            }
        }

    /** Moves an item to another milestone of the same goal (or within its own), at [MoveItemRequest.position] or the end. */
    fun moveItem(goalId: UUID, itemId: UUID, request: MoveItemRequest, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) {
        val item = GoalAccess.item(goalId, itemId)
        val target = GoalAccess.milestone(goalId, UUID.fromString(request.milestoneId))[RoadmapMilestoneTable.id].value
        RoadmapItemTable.deleteWhere { RoadmapItemTable.id eq itemId }
        closeGap(item[RoadmapItemTable.milestoneId].value, item[RoadmapItemTable.position])
        val position = insertPosition(target, request.position)
        RoadmapItemTable.insert {
            it[id] = itemId
            it[milestoneId] = target
            it[RoadmapItemTable.position] = position
            it[kind] = item[RoadmapItemTable.kind]
            it[title] = item[RoadmapItemTable.title]
            it[state] = item[RoadmapItemTable.state]
            it[stateChangedAt] = item[RoadmapItemTable.stateChangedAt]
            it[lessonId] = item[RoadmapItemTable.lessonId]
            it[createdAt] = item[RoadmapItemTable.createdAt]
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    /** All items are checked first: one unknown id changes nothing (404). Unchanged states keep their stateChangedAt. */
    fun batchStates(goalId: UUID, request: BatchItemStatesRequest, principal: UserPrincipal): TeacherGoalView = write(goalId, principal) { goal ->
        val lessonId = request.lessonId?.let(UUID::fromString)?.also { requireLesson(goal, it, "/lessonId") }
        val ids = request.items.map { UUID.fromString(it.itemId) }
        val items = (RoadmapItemTable innerJoin RoadmapMilestoneTable).selectAll()
            .where { (RoadmapItemTable.id inList ids) and (RoadmapMilestoneTable.goalId eq goalId) }
            .associateBy { it[RoadmapItemTable.id].value }
        ids.firstOrNull { it !in items }?.let { throw NotFoundException("Roadmap item not found: $it", code = "ROADMAP_ITEM_NOT_FOUND") }
        request.items.forEach { change -> setState(items.getValue(UUID.fromString(change.itemId)), RoadmapItemState.valueOf(change.state!!), lessonId) }
    }

    // ---- Student ----

    /** The student answers for a done milestone of their own goal; answering again replaces the answer. */
    fun selfAssess(milestoneId: UUID, input: SelfAssessmentInput, principal: UserPrincipal): MyGoalResponse {
        if (principal.role != UserRole.STUDENT) throw ForbiddenException("Only the goal's student can self-assess")
        var notifyTeacher: Pair<UUID, UUID>? = null
        val response = transaction {
            val milestone = (RoadmapMilestoneTable innerJoin GoalTable).selectAll()
                .where { (RoadmapMilestoneTable.id eq milestoneId) and (GoalTable.studentId eq principal.id) }
                .singleOrNull() ?: throw NotFoundException("Milestone not found", code = "ROADMAP_MILESTONE_NOT_FOUND")
            if (milestone[RoadmapMilestoneTable.doneAt] == null)
                throw ConflictException("The milestone is not finished yet", code = "MILESTONE_NOT_DONE")
            val answer = SelfAssessmentAnswer.valueOf(input.answer!!)
            val previous = MilestoneSelfAssessmentTable.selectAll()
                .where { (MilestoneSelfAssessmentTable.milestoneId eq milestoneId) and (MilestoneSelfAssessmentTable.studentId eq principal.id) }
                .singleOrNull()
            MilestoneSelfAssessmentTable.deleteWhere {
                (MilestoneSelfAssessmentTable.milestoneId eq milestoneId) and (MilestoneSelfAssessmentTable.studentId eq principal.id)
            }
            MilestoneSelfAssessmentTable.insert {
                it[MilestoneSelfAssessmentTable.milestoneId] = milestoneId
                it[studentId] = principal.id
                it[MilestoneSelfAssessmentTable.answer] = answer
                it[comment] = clean(input.comment)
                it[createdAt] = OffsetDateTime.now()
            }
            val teacher = milestone[GoalTable.teacherId]?.value
            if (teacher != null && answer != SelfAssessmentAnswer.YES && previous?.get(MilestoneSelfAssessmentTable.answer) != answer)
                notifyTeacher = teacher to milestone[GoalTable.id].value
            MyGoalResponse(GoalViews.student(milestone))
        }
        notifyTeacher?.let { (teacher, goalId) ->
            notifications.createNotification(teacher, principal.id, NotificationType.SELF_ASSESSMENT_MISMATCH, goalId)
        }
        return response
    }

    // ---- Helpers ----

    /** Runs [block] for the goal's teacher (or an admin), touches the goal and returns its fresh view. */
    private fun write(goalId: UUID, principal: UserPrincipal, block: (goal: ResultRow) -> Unit): TeacherGoalView = transaction {
        val goal = GoalAccess.requireTeacher(goalId, principal)
        block(goal)
        GoalTable.update({ GoalTable.id eq goalId }) { it[updatedAt] = OffsetDateTime.now() }
        GoalViews.teacher(goalId)
    }

    /**
     * A new state stamps stateChangedAt and records [lessonId] (null without one); the same state
     * again only updates a given lessonId, so repeating a request is a no-op.
     */
    private fun setState(item: ResultRow, state: RoadmapItemState, lessonId: UUID?) {
        val changed = item[RoadmapItemTable.state] != state
        if (!changed && (lessonId == null || lessonId == item[RoadmapItemTable.lessonId]?.value)) return
        val now = OffsetDateTime.now()
        RoadmapItemTable.update({ RoadmapItemTable.id eq item[RoadmapItemTable.id] }) {
            it[RoadmapItemTable.state] = state
            if (changed) it[stateChangedAt] = now
            it[RoadmapItemTable.lessonId] = lessonId
            it[updatedAt] = now
        }
    }

    /** A lesson of the goal's teacher that the goal's student attends. */
    private fun requireLesson(goal: ResultRow, lessonId: UUID, pointer: String) {
        val teacher = goal[GoalTable.teacherId]?.value
        val ok = teacher != null && (LessonTable innerJoin LessonStudentTable).selectAll()
            .where {
                (LessonTable.id eq lessonId) and (LessonStudentTable.studentId eq goal[GoalTable.studentId].value) and
                        (LessonTable.teacherId eq teacher)
            }
            .empty().not()
        if (!ok) throw BadRequestException("lessonId is not a lesson of this teacher with the goal's student", "ROADMAP_LESSON_INVALID", pointer)
    }

    /** [ReorderRequest.ids] must be exactly [current]; position = index + 1. */
    private fun applyOrder(request: ReorderRequest, current: List<UUID>, set: (UUID, Int) -> Unit) {
        val ids = request.ids.map(UUID::fromString)
        if (ids.toSet() != current.toSet())
            throw BadRequestException("ids must list every milestone or item to order exactly once", "ROADMAP_ORDER_INVALID", "/ids")
        ids.forEachIndexed { i, id -> set(id, i + 1) }
    }

    private fun milestoneCount(goalId: UUID): Int =
        RoadmapMilestoneTable.selectAll().where { RoadmapMilestoneTable.goalId eq goalId }.count().toInt()

    /** Makes room at [requested] (default: the end) among the milestone's items and returns the position. */
    private fun insertPosition(milestoneId: UUID, requested: Int?): Int {
        val count = RoadmapItemTable.selectAll().where { RoadmapItemTable.milestoneId eq milestoneId }.count().toInt()
        val position = (requested ?: (count + 1)).coerceAtMost(count + 1)
        RoadmapItemTable.update({ (RoadmapItemTable.milestoneId eq milestoneId) and (RoadmapItemTable.position greaterEq position) }) {
            it[RoadmapItemTable.position] = RoadmapItemTable.position + 1
        }
        return position
    }

    private fun closeGap(milestoneId: UUID, removed: Int) {
        RoadmapItemTable.update({ (RoadmapItemTable.milestoneId eq milestoneId) and (RoadmapItemTable.position greater removed) }) {
            it[position] = position - 1
        }
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}

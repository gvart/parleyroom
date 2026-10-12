package com.gvart.parleyroom.goal.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.transfer.GoalInput
import com.gvart.parleyroom.goal.transfer.GoalPageResponse
import com.gvart.parleyroom.goal.transfer.GoalPatch
import com.gvart.parleyroom.goal.transfer.MyGoalResponse
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** A student's goal (API.md "Goals & roadmap"): the teacher's input, the student's minimal view. */
class GoalService {

    /** Teachers: the goals they set; admins: all. ACTIVE first, then deadline, newest first. */
    fun list(principal: UserPrincipal, studentId: UUID?, statuses: List<GoalStatus>?, page: PageRequest): GoalPageResponse = transaction {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Students see their goal at /api/v1/goals/my")
        val query = GoalTable.selectAll()
        if (principal.role == UserRole.TEACHER) query.andWhere { GoalTable.teacherId eq principal.id }
        if (studentId != null) query.andWhere { GoalTable.studentId eq studentId }
        if (!statuses.isNullOrEmpty()) query.andWhere { GoalTable.status inList statuses }
        val total = query.count()
        // GOAL_STATUS sorts in declaration order: ACTIVE first.
        val rows = query.orderBy(GoalTable.status to SortOrder.ASC, GoalTable.deadline to SortOrder.ASC, GoalTable.createdAt to SortOrder.DESC)
            .limit(page.pageSize).offset(page.offset).toList()
        GoalPageResponse(GoalViews.summaries(rows), total, page.page, page.pageSize)
    }

    fun get(goalId: UUID, principal: UserPrincipal): TeacherGoalView = transaction {
        GoalAccess.requireTeacher(goalId, principal)
        GoalViews.teacher(goalId)
    }

    /** Teachers only, for a linked student; at most one ACTIVE goal per student. */
    fun create(input: GoalInput, principal: UserPrincipal): TeacherGoalView = transaction {
        if (principal.role != UserRole.TEACHER) throw ForbiddenException("Only teachers can set goals")
        val studentId = UUID.fromString(input.studentId)
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
        requireNoOtherActive(studentId, null)
        val now = OffsetDateTime.now()
        val id = GoalTable.insertAndGetId {
            it[GoalTable.studentId] = studentId
            it[teacherId] = principal.id
            it[title] = input.title!!.trim()
            it[exam] = clean(input.exam)
            it[targetLevel] = input.targetLevel?.let(LanguageLevel::valueOf)
            it[startDate] = LocalDate.parse(input.startDate).toKotlinLocalDate()
            it[deadline] = LocalDate.parse(input.deadline).toKotlinLocalDate()
            it[lessonsPerWeek] = BigDecimal.valueOf(input.lessonsPerWeek!!)
            it[lessonMinutes] = input.lessonMinutes!!
            it[levelNote] = clean(input.levelNote)
            it[interests] = clean(input.interests)
            it[materials] = clean(input.materials)
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        GoalViews.teacher(id)
    }

    /** Also archives (status ARCHIVED) or reopens a goal. */
    fun patch(goalId: UUID, patch: GoalPatch, principal: UserPrincipal): TeacherGoalView = transaction {
        val row = GoalAccess.requireTeacher(goalId, principal)
        val start = patch.startDate?.let(LocalDate::parse) ?: row[GoalTable.startDate].toJavaLocalDate()
        val deadline = patch.deadline?.let(LocalDate::parse) ?: row[GoalTable.deadline].toJavaLocalDate()
        if (deadline <= start)
            throw BadRequestException("deadline must be after startDate ($start)", "GOAL_INVALID", if (patch.deadline != null) "/deadline" else "/startDate")
        val status = patch.status?.let(GoalStatus::valueOf)
        if (status == GoalStatus.ACTIVE && row[GoalTable.status] != GoalStatus.ACTIVE) requireNoOtherActive(row[GoalTable.studentId].value, goalId)

        GoalTable.update({ GoalTable.id eq goalId }) {
            patch.title?.let { value -> it[title] = value.trim() }
            patch.exam?.let { value -> it[exam] = clean(value) }
            patch.targetLevel?.let { value -> it[targetLevel] = value.takeIf { v -> v.isNotEmpty() }?.let(LanguageLevel::valueOf) }
            patch.startDate?.let { _ -> it[startDate] = start.toKotlinLocalDate() }
            patch.deadline?.let { _ -> it[GoalTable.deadline] = deadline.toKotlinLocalDate() }
            patch.lessonsPerWeek?.let { value -> it[lessonsPerWeek] = BigDecimal.valueOf(value) }
            patch.lessonMinutes?.let { value -> it[lessonMinutes] = value }
            patch.levelNote?.let { value -> it[levelNote] = clean(value) }
            patch.interests?.let { value -> it[interests] = clean(value) }
            patch.materials?.let { value -> it[materials] = clean(value) }
            status?.let { value -> it[GoalTable.status] = value }
            it[updatedAt] = OffsetDateTime.now()
        }
        GoalViews.teacher(goalId)
    }

    fun delete(goalId: UUID, principal: UserPrincipal) = transaction {
        GoalAccess.requireTeacher(goalId, principal)
        GoalTable.deleteWhere { GoalTable.id eq goalId }
    }

    /** The student's ACTIVE goal in the minimal student view. */
    fun my(principal: UserPrincipal): MyGoalResponse = transaction {
        if (principal.role != UserRole.STUDENT) throw ForbiddenException("Only students have their own goal")
        val goal = GoalTable.selectAll()
            .where { (GoalTable.studentId eq principal.id) and (GoalTable.status eq GoalStatus.ACTIVE) }
            .singleOrNull()
        MyGoalResponse(goal?.let(GoalViews::student))
    }

    private fun requireNoOtherActive(studentId: UUID, exceptGoalId: UUID?) {
        val query = GoalTable.selectAll().where { (GoalTable.studentId eq studentId) and (GoalTable.status eq GoalStatus.ACTIVE) }
        if (exceptGoalId != null) query.andWhere { GoalTable.id neq exceptGoalId }
        if (!query.empty()) throw ConflictException("The student already has an active goal", code = "GOAL_ACTIVE_EXISTS")
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}

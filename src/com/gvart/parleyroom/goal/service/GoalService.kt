package com.gvart.parleyroom.goal.service

import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.goal.data.GoalSetBy
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.LearningGoalTable
import com.gvart.parleyroom.goal.transfer.CreateGoalRequest
import com.gvart.parleyroom.goal.transfer.GoalPageResponse
import com.gvart.parleyroom.goal.transfer.GoalResponse
import com.gvart.parleyroom.goal.transfer.UpdateGoalProgressRequest
import com.gvart.parleyroom.goal.transfer.UpdateGoalRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlinx.datetime.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

class GoalService {

    fun getGoals(
        principal: UserPrincipal,
        studentId: UUID?,
        status: GoalStatus?,
        page: PageRequest,
    ): GoalPageResponse = transaction {
        val query = when (principal.role) {
            UserRole.ADMIN -> LearningGoalTable.selectAll()
            UserRole.TEACHER -> LearningGoalTable.selectAll()
                .where { LearningGoalTable.teacherId eq principal.id }
            UserRole.STUDENT -> LearningGoalTable.selectAll()
                .where { LearningGoalTable.studentId eq principal.id }
        }

        if (studentId != null) {
            AuthorizationHelper.requireAccessToStudent(studentId, principal)
            query.andWhere { LearningGoalTable.studentId eq studentId }
        }

        if (status != null) {
            query.andWhere { LearningGoalTable.status eq status }
        }

        val total = query.count()
        val items = query
            .limit(page.pageSize)
            .offset(page.offset)
            .map(::toResponse)

        GoalPageResponse(
            goals = items,
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun getGoal(goalId: UUID, principal: UserPrincipal): GoalResponse = transaction {
        val goal = findGoal(goalId)
        AuthorizationHelper.requireAccessToStudent(goal[LearningGoalTable.studentId].value, principal)
        toResponse(goal)
    }

    fun createGoal(request: CreateGoalRequest, principal: UserPrincipal): GoalResponse = transaction {
        val studentId = UUID.fromString(request.studentId)
        AuthorizationHelper.requireAccessToStudent(studentId, principal)

        val setBy = when (principal.role) {
            UserRole.STUDENT -> GoalSetBy.STUDENT
            else -> GoalSetBy.TEACHER
        }

        val teacherId = when (principal.role) {
            UserRole.STUDENT -> null
            else -> principal.id
        }

        val now = OffsetDateTime.now()
        val id = LearningGoalTable.insertAndGetId {
            it[LearningGoalTable.studentId] = studentId
            it[LearningGoalTable.teacherId] = teacherId
            it[description] = request.description
            it[LearningGoalTable.setBy] = setBy
            it[targetDate] = request.targetDate?.let { d -> LocalDate.parse(d) }
        }

        LearningGoalTable.selectAll()
            .where { LearningGoalTable.id eq id }
            .single()
            .let(::toResponse)
    }

    fun updateGoal(goalId: UUID, request: UpdateGoalRequest, principal: UserPrincipal): GoalResponse = transaction {
        val goal = findGoal(goalId)
        AuthorizationHelper.requireAccessToStudent(goal[LearningGoalTable.studentId].value, principal)

        if (goal[LearningGoalTable.status] != GoalStatus.ACTIVE)
            throw BadRequestException("Can only update active goals")

        LearningGoalTable.update({ LearningGoalTable.id eq goalId }) {
            if (request.description != null) it[description] = request.description
            if (request.targetDate != null) it[targetDate] = LocalDate.parse(request.targetDate)
            it[updatedAt] = OffsetDateTime.now()
        }

        LearningGoalTable.selectAll()
            .where { LearningGoalTable.id eq goalId }
            .single()
            .let(::toResponse)
    }

    fun updateProgress(goalId: UUID, request: UpdateGoalProgressRequest, principal: UserPrincipal): GoalResponse = transaction {
        val goal = findGoal(goalId)
        AuthorizationHelper.requireAccessToStudent(goal[LearningGoalTable.studentId].value, principal)

        if (goal[LearningGoalTable.status] != GoalStatus.ACTIVE)
            throw BadRequestException("Can only update progress on active goals")

        LearningGoalTable.update({ LearningGoalTable.id eq goalId }) {
            it[progress] = request.progress
            it[updatedAt] = OffsetDateTime.now()
        }

        LearningGoalTable.selectAll()
            .where { LearningGoalTable.id eq goalId }
            .single()
            .let(::toResponse)
    }

    fun completeGoal(goalId: UUID, principal: UserPrincipal): GoalResponse = transaction {
        val goal = findGoal(goalId)
        AuthorizationHelper.requireAccessToStudent(goal[LearningGoalTable.studentId].value, principal)

        if (goal[LearningGoalTable.status] != GoalStatus.ACTIVE)
            throw BadRequestException("Can only complete active goals")

        LearningGoalTable.update({ LearningGoalTable.id eq goalId }) {
            it[status] = GoalStatus.COMPLETED
            it[progress] = 100
            it[updatedAt] = OffsetDateTime.now()
        }

        LearningGoalTable.selectAll()
            .where { LearningGoalTable.id eq goalId }
            .single()
            .let(::toResponse)
    }

    fun abandonGoal(goalId: UUID, principal: UserPrincipal): GoalResponse = transaction {
        val goal = findGoal(goalId)
        AuthorizationHelper.requireAccessToStudent(goal[LearningGoalTable.studentId].value, principal)

        if (goal[LearningGoalTable.status] != GoalStatus.ACTIVE)
            throw BadRequestException("Can only abandon active goals")

        LearningGoalTable.update({ LearningGoalTable.id eq goalId }) {
            it[status] = GoalStatus.ABANDONED
            it[updatedAt] = OffsetDateTime.now()
        }

        LearningGoalTable.selectAll()
            .where { LearningGoalTable.id eq goalId }
            .single()
            .let(::toResponse)
    }

    fun deleteGoal(goalId: UUID, principal: UserPrincipal) = transaction {
        val goal = findGoal(goalId)
        AuthorizationHelper.requireAccessToStudent(goal[LearningGoalTable.studentId].value, principal)
        LearningGoalTable.deleteWhere { id eq goalId }
    }

    private fun findGoal(goalId: UUID): ResultRow =
        LearningGoalTable.selectAll()
            .where { LearningGoalTable.id eq goalId }
            .singleOrNull() ?: throw NotFoundException("Goal not found")

    private fun toResponse(row: ResultRow) = GoalResponse(
        id = row[LearningGoalTable.id].value.toString(),
        studentId = row[LearningGoalTable.studentId].value.toString(),
        teacherId = row[LearningGoalTable.teacherId]?.value?.toString(),
        description = row[LearningGoalTable.description],
        progress = row[LearningGoalTable.progress],
        setBy = row[LearningGoalTable.setBy],
        targetDate = row[LearningGoalTable.targetDate]?.toString(),
        status = row[LearningGoalTable.status],
        createdAt = row[LearningGoalTable.createdAt],
        updatedAt = row[LearningGoalTable.updatedAt],
    )
}

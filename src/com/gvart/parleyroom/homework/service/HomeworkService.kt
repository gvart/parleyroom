package com.gvart.parleyroom.homework.service

import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.transfer.CreateHomeworkRequest
import com.gvart.parleyroom.homework.transfer.HomeworkPageResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.ReviewHomeworkRequest
import com.gvart.parleyroom.homework.transfer.SubmitHomeworkRequest
import com.gvart.parleyroom.homework.transfer.UpdateHomeworkRequest
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

class HomeworkService {

    fun getHomework(
        principal: UserPrincipal,
        studentId: UUID?,
        status: HomeworkStatus?,
        page: PageRequest,
    ): HomeworkPageResponse = transaction {
        val query = when (principal.role) {
            UserRole.ADMIN -> HomeworkTable.selectAll()
            UserRole.TEACHER -> HomeworkTable.selectAll()
                .where { HomeworkTable.teacherId eq principal.id }
            UserRole.STUDENT -> HomeworkTable.selectAll()
                .where { HomeworkTable.studentId eq principal.id }
        }

        if (studentId != null) {
            AuthorizationHelper.requireAccessToStudent(studentId, principal)
            query.andWhere { HomeworkTable.studentId eq studentId }
        }

        if (status != null) {
            query.andWhere { HomeworkTable.status eq status }
        }

        val total = query.count()
        val items = query
            .limit(page.pageSize)
            .offset(page.offset)
            .map(::toResponse)

        HomeworkPageResponse(
            homework = items,
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun getHomeworkById(homeworkId: UUID, principal: UserPrincipal): HomeworkResponse = transaction {
        val hw = findHomework(homeworkId)
        requireHomeworkAccess(hw, principal)
        toResponse(hw)
    }

    fun createHomework(request: CreateHomeworkRequest, principal: UserPrincipal): HomeworkResponse = transaction {
        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers and admins can create homework")

        val studentId = UUID.fromString(request.studentId)
        val teacherId = principal.id

        AuthorizationHelper.requireAccessToStudent(studentId, principal)

        val now = OffsetDateTime.now()
        val id = HomeworkTable.insertAndGetId {
            it[HomeworkTable.lessonId] = request.lessonId?.let(UUID::fromString)
            it[HomeworkTable.studentId] = studentId
            it[HomeworkTable.teacherId] = teacherId
            it[title] = request.title
            it[description] = request.description
            it[category] = request.category
            it[dueDate] = request.dueDate?.let { d -> LocalDate.parse(d) }
            it[attachmentType] = request.attachmentType
            it[attachmentUrl] = request.attachmentUrl
            it[attachmentName] = request.attachmentName
        }

        HomeworkTable.selectAll()
            .where { HomeworkTable.id eq id }
            .single()
            .let(::toResponse)
    }

    fun updateHomework(homeworkId: UUID, request: UpdateHomeworkRequest, principal: UserPrincipal): HomeworkResponse = transaction {
        val hw = findHomework(homeworkId)

        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers and admins can update homework")

        requireHomeworkTeacherOrAdmin(hw, principal)

        HomeworkTable.update({ HomeworkTable.id eq homeworkId }) {
            if (request.title != null) it[title] = request.title
            if (request.description != null) it[description] = request.description
            if (request.category != null) it[category] = request.category
            if (request.dueDate != null) it[dueDate] = LocalDate.parse(request.dueDate)
            it[updatedAt] = OffsetDateTime.now()
        }

        HomeworkTable.selectAll()
            .where { HomeworkTable.id eq homeworkId }
            .single()
            .let(::toResponse)
    }

    fun deleteHomework(homeworkId: UUID, principal: UserPrincipal) = transaction {
        val hw = findHomework(homeworkId)

        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers and admins can delete homework")

        requireHomeworkTeacherOrAdmin(hw, principal)

        HomeworkTable.deleteWhere { id eq homeworkId }
    }

    fun submitHomework(homeworkId: UUID, request: SubmitHomeworkRequest, principal: UserPrincipal): HomeworkResponse = transaction {
        val hw = findHomework(homeworkId)

        if (hw[HomeworkTable.studentId].value != principal.id)
            throw ForbiddenException("Only the assigned student can submit homework")

        val currentStatus = hw[HomeworkTable.status]
        if (currentStatus != HomeworkStatus.OPEN && currentStatus != HomeworkStatus.REJECTED)
            throw BadRequestException("Can only submit homework with OPEN or REJECTED status")

        HomeworkTable.update({ HomeworkTable.id eq homeworkId }) {
            it[submissionText] = request.submissionText
            it[submissionUrl] = request.submissionUrl
            it[status] = HomeworkStatus.SUBMITTED
            it[updatedAt] = OffsetDateTime.now()
        }

        HomeworkTable.selectAll()
            .where { HomeworkTable.id eq homeworkId }
            .single()
            .let(::toResponse)
    }

    fun reviewHomework(homeworkId: UUID, request: ReviewHomeworkRequest, principal: UserPrincipal): HomeworkResponse = transaction {
        val hw = findHomework(homeworkId)

        requireHomeworkTeacherOrAdmin(hw, principal)

        val currentStatus = hw[HomeworkTable.status]
        if (currentStatus != HomeworkStatus.SUBMITTED && currentStatus != HomeworkStatus.IN_REVIEW)
            throw BadRequestException("Can only review homework with SUBMITTED or IN_REVIEW status")

        if (request.status != HomeworkStatus.DONE && request.status != HomeworkStatus.REJECTED)
            throw BadRequestException("Review status must be DONE or REJECTED")

        HomeworkTable.update({ HomeworkTable.id eq homeworkId }) {
            it[status] = request.status
            it[teacherFeedback] = request.teacherFeedback
            it[updatedAt] = OffsetDateTime.now()
        }

        HomeworkTable.selectAll()
            .where { HomeworkTable.id eq homeworkId }
            .single()
            .let(::toResponse)
    }

    private fun findHomework(homeworkId: UUID): ResultRow =
        HomeworkTable.selectAll()
            .where { HomeworkTable.id eq homeworkId }
            .singleOrNull() ?: throw NotFoundException("Homework not found")

    private fun requireHomeworkAccess(hw: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        if (principal.role == UserRole.STUDENT && hw[HomeworkTable.studentId].value == principal.id) return
        if (principal.role == UserRole.TEACHER && hw[HomeworkTable.teacherId].value == principal.id) return
        throw ForbiddenException("No access to this homework")
    }

    private fun requireHomeworkTeacherOrAdmin(hw: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        if (hw[HomeworkTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the assigning teacher can perform this action")
    }

    private fun toResponse(row: ResultRow) = HomeworkResponse(
        id = row[HomeworkTable.id].value.toString(),
        lessonId = row[HomeworkTable.lessonId]?.value?.toString(),
        studentId = row[HomeworkTable.studentId].value.toString(),
        teacherId = row[HomeworkTable.teacherId].value.toString(),
        title = row[HomeworkTable.title],
        description = row[HomeworkTable.description],
        category = row[HomeworkTable.category],
        dueDate = row[HomeworkTable.dueDate]?.toString(),
        status = row[HomeworkTable.status],
        submissionText = row[HomeworkTable.submissionText],
        submissionUrl = row[HomeworkTable.submissionUrl],
        teacherFeedback = row[HomeworkTable.teacherFeedback],
        attachmentType = row[HomeworkTable.attachmentType],
        attachmentUrl = row[HomeworkTable.attachmentUrl],
        attachmentName = row[HomeworkTable.attachmentName],
        createdAt = row[HomeworkTable.createdAt],
        updatedAt = row[HomeworkTable.updatedAt],
    )
}

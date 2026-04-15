package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class LessonParticipantService(
    private val notificationService: NotificationService,
    private val support: LessonSupport,
) {

    fun joinLesson(lessonId: UUID, principal: UserPrincipal) = transaction {
        val lesson = support.findLessonForUpdate(lessonId)

        if (lesson[LessonTable.type] == LessonType.ONE_ON_ONE)
            throw BadRequestException("Cannot request to join a one-on-one lesson")

        if (lesson[LessonTable.status] != LessonStatus.CONFIRMED)
            throw BadRequestException("Can only join confirmed lessons")

        if (lesson[LessonTable.teacherId].value == principal.id)
            throw BadRequestException("Teacher is already a participant")

        val existing = LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq principal.id)
            }.singleOrNull()

        if (existing != null) {
            val existingStatus = existing[LessonStudentTable.status]
            if (existingStatus == LessonStudentStatus.CONFIRMED)
                throw ConflictException("Already a participant of this lesson")
            if (existingStatus == LessonStudentStatus.REQUESTED)
                throw ConflictException("Join request already pending")
            LessonStudentTable.update({
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq principal.id)
            }) {
                it[status] = LessonStudentStatus.REQUESTED
            }
            return@transaction
        }

        val maxParticipants = lesson[LessonTable.maxParticipants]
        if (maxParticipants != null) {
            val currentCount = LessonStudentTable.selectAll()
                .where {
                    (LessonStudentTable.lessonId eq lessonId) and
                            (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                }.count()
            if (currentCount >= maxParticipants)
                throw BadRequestException("Lesson is full")
        }

        LessonStudentTable.insert {
            it[LessonStudentTable.lessonId] = lessonId
            it[LessonStudentTable.studentId] = principal.id
            it[LessonStudentTable.status] = LessonStudentStatus.REQUESTED
        }

        notificationService.createNotification(
            userId = lesson[LessonTable.teacherId].value,
            actorId = principal.id,
            type = NotificationType.JOIN_REQUESTED,
            referenceId = lessonId,
        )
    }

    fun acceptJoinRequest(lessonId: UUID, studentId: UUID, principal: UserPrincipal) = transaction {
        val lesson = support.findLessonForUpdate(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the teacher can accept join requests")

        val entry = support.findStudentEntry(lessonId, studentId)
        if (entry[LessonStudentTable.status] != LessonStudentStatus.REQUESTED)
            throw BadRequestException("No pending join request for this student")

        val maxParticipants = lesson[LessonTable.maxParticipants]
        if (maxParticipants != null) {
            val currentCount = LessonStudentTable.selectAll()
                .where {
                    (LessonStudentTable.lessonId eq lessonId) and
                            (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                }.count()
            if (currentCount >= maxParticipants)
                throw BadRequestException("Lesson is full")
        }

        LessonStudentTable.update({
            (LessonStudentTable.lessonId eq lessonId) and
                    (LessonStudentTable.studentId eq studentId)
        }) {
            it[status] = LessonStudentStatus.CONFIRMED
        }

        notificationService.createNotification(
            userId = studentId,
            actorId = principal.id,
            type = NotificationType.JOIN_ACCEPTED,
            referenceId = lessonId,
        )
    }

    fun rejectJoinRequest(lessonId: UUID, studentId: UUID, principal: UserPrincipal) = transaction {
        val lesson = support.findLesson(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the teacher can reject join requests")

        val entry = support.findStudentEntry(lessonId, studentId)
        if (entry[LessonStudentTable.status] != LessonStudentStatus.REQUESTED)
            throw BadRequestException("No pending join request for this student")

        LessonStudentTable.update({
            (LessonStudentTable.lessonId eq lessonId) and
                    (LessonStudentTable.studentId eq studentId)
        }) {
            it[status] = LessonStudentStatus.REJECTED
        }

        notificationService.createNotification(
            userId = studentId,
            actorId = principal.id,
            type = NotificationType.JOIN_REJECTED,
            referenceId = lessonId,
        )
    }
}

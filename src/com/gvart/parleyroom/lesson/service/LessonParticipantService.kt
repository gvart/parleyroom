package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

class LessonParticipantService(
    private val notificationService: NotificationService,
    private val support: LessonSupport,
) {

    fun joinLesson(lessonId: UUID, principal: UserPrincipal) = transaction {
        // Row lock on the lesson serialises concurrent joins, so two students can't
        // both take the last spot.
        val lesson = support.findLessonForUpdate(lessonId)

        if (lesson[LessonTable.type] == LessonType.ONE_ON_ONE)
            throw BadRequestException("Cannot request to join a one-on-one lesson", code = "LESSON_NOT_JOINABLE")

        if (lesson[LessonTable.status] != LessonStatus.CONFIRMED || !lesson[LessonTable.scheduledAt].isAfter(OffsetDateTime.now()))
            throw BadRequestException("Can only join upcoming confirmed lessons", code = "LESSON_INVALID_STATE")

        val teacherId = lesson[LessonTable.teacherId].value
        if (teacherId == principal.id)
            throw BadRequestException("Teacher is already a participant", code = "ALREADY_PARTICIPANT")

        val isLinked = TeacherStudentTable.selectAll()
            .where {
                (TeacherStudentTable.teacherId eq teacherId) and
                        (TeacherStudentTable.studentId eq principal.id) and
                        (TeacherStudentTable.status eq UserStatus.ACTIVE)
            }
            .empty().not()
        if (!isLinked)
            throw ForbiddenException("Only students of this teacher can join the club", code = "NOT_TEACHERS_STUDENT")

        val existing = LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq principal.id)
            }.singleOrNull()

        when (existing?.get(LessonStudentTable.status)) {
            LessonStudentStatus.CONFIRMED ->
                throw ConflictException("Already a participant of this lesson", code = "ALREADY_PARTICIPANT")
            LessonStudentStatus.REQUESTED ->
                throw ConflictException("Join request already pending", code = "JOIN_REQUEST_ALREADY_PENDING")
            else -> Unit
        }

        // Pending requests reserve a spot, so "taken" counts them too.
        val maxParticipants = lesson[LessonTable.maxParticipants]
        if (maxParticipants != null &&
            countStudents(lessonId, LessonStudentStatus.CONFIRMED, LessonStudentStatus.REQUESTED) >= maxParticipants
        ) throw ConflictException("Club is full", code = "CLUB_FULL")

        val autoAccept = UserTable.select(UserTable.autoAcceptClubJoins)
            .where { UserTable.id eq teacherId }
            .single()[UserTable.autoAcceptClubJoins]
        val newStatus = if (autoAccept) LessonStudentStatus.CONFIRMED else LessonStudentStatus.REQUESTED

        if (existing != null) {
            LessonStudentTable.update({
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq principal.id)
            }) {
                it[status] = newStatus
            }
        } else {
            LessonStudentTable.insert {
                it[LessonStudentTable.lessonId] = lessonId
                it[LessonStudentTable.studentId] = principal.id
                it[LessonStudentTable.status] = newStatus
            }
        }

        if (autoAccept) {
            notificationService.createNotification(
                userId = principal.id,
                actorId = teacherId,
                type = NotificationType.JOIN_ACCEPTED,
                referenceId = lessonId,
            )
            notificationService.createNotification(
                userId = teacherId,
                actorId = principal.id,
                type = NotificationType.CLUB_JOINED,
                referenceId = lessonId,
            )
        } else {
            notificationService.createNotification(
                userId = teacherId,
                actorId = principal.id,
                type = NotificationType.JOIN_REQUESTED,
                referenceId = lessonId,
            )
        }
    }

    fun withdrawJoinRequest(lessonId: UUID, principal: UserPrincipal) = transaction {
        support.findLessonForUpdate(lessonId)

        val deleted = LessonStudentTable.deleteWhere {
            (LessonStudentTable.lessonId eq lessonId) and
                    (LessonStudentTable.studentId eq principal.id) and
                    (LessonStudentTable.status eq LessonStudentStatus.REQUESTED)
        }
        if (deleted == 0)
            throw NotFoundException("No pending join request for this lesson", code = "JOIN_REQUEST_NOT_FOUND")
    }

    fun acceptJoinRequest(lessonId: UUID, studentId: UUID, principal: UserPrincipal) = transaction {
        val lesson = support.findLessonForUpdate(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the teacher can accept join requests")

        val entry = support.findStudentEntry(lessonId, studentId)
        if (entry[LessonStudentTable.status] != LessonStudentStatus.REQUESTED)
            throw BadRequestException("No pending join request for this student", code = "JOIN_REQUEST_NOT_FOUND")

        // The request already holds a spot; re-check only confirmed seats in case the
        // teacher lowered maxParticipants since.
        val maxParticipants = lesson[LessonTable.maxParticipants]
        if (maxParticipants != null && countStudents(lessonId, LessonStudentStatus.CONFIRMED) >= maxParticipants)
            throw ConflictException("Club is full", code = "CLUB_FULL")

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
            throw BadRequestException("No pending join request for this student", code = "JOIN_REQUEST_NOT_FOUND")

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

    private fun countStudents(lessonId: UUID, vararg statuses: LessonStudentStatus): Long =
        LessonStudentTable.selectAll()
            .where { (LessonStudentTable.lessonId eq lessonId) and (LessonStudentTable.status inList statuses.toList()) }
            .count()
}

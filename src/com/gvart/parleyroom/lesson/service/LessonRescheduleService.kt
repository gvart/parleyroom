package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.availability.service.AvailabilityValidator
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.lesson.data.LessonEventTable
import com.gvart.parleyroom.lesson.data.LessonEventType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class LessonRescheduleService(
    private val notificationService: NotificationService,
    private val support: LessonSupport,
    private val availabilityValidator: AvailabilityValidator,
) {

    fun rescheduleLesson(lessonId: UUID, request: RescheduleLessonRequest, principal: UserPrincipal) = transaction {
        val lesson = support.findLesson(lessonId)

        if (lesson[LessonTable.status] != LessonStatus.CONFIRMED)
            throw BadRequestException("Only confirmed lessons can be rescheduled")

        support.requireLessonParticipant(lessonId, lesson, principal)

        // Students moving into a teacher's blocked window get an early, targeted
        // error. Teachers and admins rescheduling their own lessons keep the
        // existing lenient flow (buffer still enforced when accepted).
        if (principal.role == UserRole.STUDENT) {
            availabilityValidator.validate(
                lesson[LessonTable.teacherId].value,
                request.newScheduledAt,
                lesson[LessonTable.durationMinutes],
                OffsetDateTime.now(),
            )
        }

        val hasPending = LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId eq lessonId) and
                        (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                        (LessonEventTable.resolved eq false)
            }
            .empty().not()

        if (hasPending)
            throw ConflictException("A reschedule is already pending for this lesson")

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.RESCHEDULE_REQUESTED
            it[actorId] = principal.id
            it[oldScheduledAt] = lesson[LessonTable.scheduledAt]
            it[newScheduledAt] = request.newScheduledAt
            it[note] = request.note
        }

        for (userId in support.getOtherParticipants(lessonId, lesson, principal.id)) {
            notificationService.createNotification(
                userId = userId,
                actorId = principal.id,
                type = NotificationType.RESCHEDULE_REQUESTED,
                referenceId = lessonId,
            )
        }
    }

    fun acceptReschedule(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLesson(lessonId)
        val pendingEvent = support.findPendingReschedule(lessonId)

        if (pendingEvent[LessonEventTable.actorId].value == principal.id)
            throw ForbiddenException("Cannot accept your own reschedule request")

        support.requireLessonParticipant(lessonId, lesson, principal)

        val newScheduledAt = pendingEvent[LessonEventTable.newScheduledAt]!!

        val bufferMinutes = if (principal.role == UserRole.ADMIN) 0
        else availabilityValidator.loadSettings(lesson[LessonTable.teacherId].value).bufferMinutes

        support.checkTeacherOverlap(
            lesson[LessonTable.teacherId].value,
            newScheduledAt,
            lesson[LessonTable.durationMinutes],
            excludeLessonId = lessonId,
            bufferMinutes = bufferMinutes,
        )

        LessonEventTable.update({ LessonEventTable.id eq pendingEvent[LessonEventTable.id] }) {
            it[resolved] = true
        }

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[scheduledAt] = newScheduledAt
            it[updatedBy] = principal.id
            it[updatedAt] = OffsetDateTime.now()
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.RESCHEDULE_ACCEPTED
            it[actorId] = principal.id
            it[oldScheduledAt] = lesson[LessonTable.scheduledAt]
            it[LessonEventTable.newScheduledAt] = newScheduledAt
        }

        val requesterId = pendingEvent[LessonEventTable.actorId].value
        notificationService.createNotification(
            userId = requesterId,
            actorId = principal.id,
            type = NotificationType.RESCHEDULE_ACCEPTED,
            referenceId = lessonId,
        )

        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .single()
            .let(support::toResponse)
    }

    fun rejectReschedule(lessonId: UUID, principal: UserPrincipal) = transaction {
        val lesson = support.findLesson(lessonId)
        val pendingEvent = support.findPendingReschedule(lessonId)

        if (pendingEvent[LessonEventTable.actorId].value == principal.id)
            throw ForbiddenException("Cannot reject your own reschedule request")

        support.requireLessonParticipant(lessonId, lesson, principal)

        LessonEventTable.update({ LessonEventTable.id eq pendingEvent[LessonEventTable.id] }) {
            it[resolved] = true
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.RESCHEDULE_REJECTED
            it[actorId] = principal.id
            it[oldScheduledAt] = pendingEvent[LessonEventTable.oldScheduledAt]
            it[newScheduledAt] = pendingEvent[LessonEventTable.newScheduledAt]
        }

        val requesterId = pendingEvent[LessonEventTable.actorId].value
        notificationService.createNotification(
            userId = requesterId,
            actorId = principal.id,
            type = NotificationType.RESCHEDULE_REJECTED,
            referenceId = lessonId,
        )
    }
}

package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.availability.service.AvailabilityValidator
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.lesson.data.LessonDocumentTable
import com.gvart.parleyroom.lesson.data.LessonEventTable
import com.gvart.parleyroom.lesson.data.LessonEventType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.CompleteLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonDocumentResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.StartLessonResponse
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.video.service.VideoTokenService
import com.gvart.parleyroom.video.transfer.VideoAccess
import com.gvart.parleyroom.video.transfer.VideoParticipantRole
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class LessonLifecycleService(
    private val notificationService: NotificationService,
    private val videoTokenService: VideoTokenService,
    private val documentService: LessonDocumentService,
    private val support: LessonSupport,
    private val availabilityValidator: AvailabilityValidator,
) {

    companion object {
        /** Students may dial in this many minutes before scheduledAt; teachers have no such gate. */
        const val EARLY_JOIN_MINUTES: Long = 10
    }

    fun createLesson(request: CreateLessonRequest, principal: UserPrincipal): LessonResponse = transaction {
        val teacherId = UUID.fromString(request.teacherId)
        val studentIds = request.studentIds.map { UUID.fromString(it) }

        val validStudents = UserTable.selectAll()
            .where { (UserTable.id inList studentIds) and (UserTable.role eq UserRole.STUDENT) }
            .map { it[UserTable.id].value }
            .toSet()

        val invalidIds = studentIds.filter { it !in validStudents }
        if (invalidIds.isNotEmpty()) {
            throw BadRequestException("Invalid student IDs (not found or not students): ${invalidIds.joinToString()}")
        }

        if (principal.role != UserRole.ADMIN) {
            val linkedStudents = TeacherStudentTable.selectAll()
                .where {
                    (TeacherStudentTable.teacherId eq teacherId) and
                            (TeacherStudentTable.studentId inList studentIds)
                }
                .map { it[TeacherStudentTable.studentId].value }
                .toSet()

            val unlinkedIds = studentIds.filter { it !in linkedStudents }
            if (unlinkedIds.isNotEmpty()) {
                throw BadRequestException("Teacher does not have a relationship with students: ${unlinkedIds.joinToString()}")
            }
        }

        val status = when (principal.role) {
            UserRole.TEACHER -> {
                if (principal.id != teacherId)
                    throw ForbiddenException("Teachers can only create lessons for themselves")
                LessonStatus.CONFIRMED
            }
            UserRole.STUDENT -> {
                if (request.type != LessonType.ONE_ON_ONE)
                    throw ForbiddenException("Students can only request one-on-one lessons")
                if (principal.id !in studentIds)
                    throw ForbiddenException("Students can only request lessons for themselves")
                LessonStatus.REQUEST
            }
            UserRole.ADMIN -> LessonStatus.CONFIRMED
        }

        val scheduledAt = request.scheduledAt

        // Teachers booking their own calendar + admins bypass the availability
        // guardrails — both should be able to self-override their schedule.
        // Students go through the full validation (weekly + exceptions + min notice).
        if (principal.role == UserRole.STUDENT) {
            availabilityValidator.validate(teacherId, scheduledAt, request.durationMinutes, OffsetDateTime.now())
        }

        val bufferMinutes = if (principal.role == UserRole.ADMIN) 0
        else availabilityValidator.loadSettings(teacherId).bufferMinutes

        support.checkTeacherOverlap(teacherId, scheduledAt, request.durationMinutes, bufferMinutes = bufferMinutes)

        val lessonId = LessonTable.insertAndGetId {
            it[LessonTable.title] = request.title
            it[LessonTable.type] = request.type
            it[LessonTable.scheduledAt] = scheduledAt
            it[LessonTable.durationMinutes] = request.durationMinutes
            it[LessonTable.teacherId] = teacherId
            it[LessonTable.status] = status
            it[LessonTable.topic] = request.topic
            it[LessonTable.level] = request.level
            it[LessonTable.maxParticipants] = request.maxParticipants
            it[LessonTable.createdBy] = principal.id
        }

        for (studentId in studentIds) {
            LessonStudentTable.insert {
                it[LessonStudentTable.lessonId] = lessonId
                it[LessonStudentTable.studentId] = studentId
                it[LessonStudentTable.status] = LessonStudentStatus.CONFIRMED
            }
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.STATUS_CHANGE
            it[actorId] = principal.id
            it[newStatus] = status
        }

        if (status == LessonStatus.REQUEST) {
            notificationService.createNotification(
                userId = teacherId,
                actorId = principal.id,
                type = NotificationType.LESSON_REQUESTED,
                referenceId = lessonId.value,
            )
        } else if (status == LessonStatus.CONFIRMED) {
            for (studentId in studentIds) {
                notificationService.createNotification(
                    userId = studentId,
                    actorId = principal.id,
                    type = NotificationType.LESSON_CREATED,
                    referenceId = lessonId.value,
                )
            }
        }

        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .single()
            .let(support::toResponse)
    }

    fun acceptLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLesson(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the assigned teacher can accept lesson requests")

        if (lesson[LessonTable.status] != LessonStatus.REQUEST)
            throw BadRequestException("Only lessons with REQUEST status can be accepted")

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[status] = LessonStatus.CONFIRMED
            it[updatedBy] = principal.id
            it[updatedAt] = OffsetDateTime.now()
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.STATUS_CHANGE
            it[actorId] = principal.id
            it[oldStatus] = LessonStatus.REQUEST
            it[newStatus] = LessonStatus.CONFIRMED
        }

        for (studentId in support.getStudentIds(lessonId)) {
            notificationService.createNotification(
                userId = studentId,
                actorId = principal.id,
                type = NotificationType.LESSON_ACCEPTED,
                referenceId = lessonId,
            )
        }

        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .single()
            .let(support::toResponse)
    }

    fun cancelLesson(lessonId: UUID, request: CancelLessonRequest, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLesson(lessonId)
        val currentStatus = lesson[LessonTable.status]

        if (currentStatus == LessonStatus.COMPLETED || currentStatus == LessonStatus.CANCELLED)
            throw BadRequestException("Cannot cancel a ${currentStatus.name.lowercase()} lesson")

        support.requireLessonParticipant(lessonId, lesson, principal)

        val now = OffsetDateTime.now()

        LessonEventTable.update({
            (LessonEventTable.lessonId eq lessonId) and
                    (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                    (LessonEventTable.resolved eq false)
        }) {
            it[resolved] = true
        }

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[status] = LessonStatus.CANCELLED
            it[updatedBy] = principal.id
            it[updatedAt] = now
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.LESSON_CANCELLED
            it[actorId] = principal.id
            it[oldStatus] = currentStatus
            it[newStatus] = LessonStatus.CANCELLED
            it[note] = request.reason
        }

        for (userId in support.getOtherParticipants(lessonId, lesson, principal.id)) {
            notificationService.createNotification(
                userId = userId,
                actorId = principal.id,
                type = NotificationType.LESSON_CANCELLED,
                referenceId = lessonId,
            )
        }

        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .single()
            .let(support::toResponse)
    }

    fun startLesson(lessonId: UUID, principal: UserPrincipal): StartLessonResponse = transaction {
        val lesson = support.findLesson(lessonId)

        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers or admins can start a lesson")

        if (lesson[LessonTable.teacherId].value != principal.id && principal.role != UserRole.ADMIN)
            throw ForbiddenException("Only the assigned teacher can start this lesson")

        if (lesson[LessonTable.status] != LessonStatus.CONFIRMED)
            throw BadRequestException("Only confirmed lessons can be started")

        if (lesson[LessonTable.startedAt] != null)
            throw ConflictException("Lesson has already been started")

        val now = OffsetDateTime.now()

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[startedAt] = now
            it[status] = LessonStatus.IN_PROGRESS
            it[updatedBy] = principal.id
            it[updatedAt] = now
        }

        LessonStudentTable.update({
            (LessonStudentTable.lessonId eq lessonId) and
                    (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
        }) {
            it[attended] = true
        }

        val docId = documentService.ensureDocument(lessonId)

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.LESSON_STARTED
            it[actorId] = principal.id
            it[oldStatus] = LessonStatus.CONFIRMED
            it[newStatus] = LessonStatus.IN_PROGRESS
        }

        for (studentId in support.getStudentIds(lessonId)) {
            notificationService.createNotification(
                userId = studentId,
                actorId = principal.id,
                type = NotificationType.LESSON_STARTED,
                referenceId = lessonId,
            )
        }

        val document = LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.id eq docId }
            .single()
            .let(support::toDocumentResponse)

        val videoRoom = mintVideoAccess(lessonId, principal)
        StartLessonResponse(document = document, videoRoom = videoRoom)
    }

    fun completeLesson(lessonId: UUID, request: CompleteLessonRequest, principal: UserPrincipal): LessonDocumentResponse = transaction {
        val lesson = support.findLesson(lessonId)

        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers or admins can complete a lesson")

        if (lesson[LessonTable.teacherId].value != principal.id && principal.role != UserRole.ADMIN)
            throw ForbiddenException("Only the assigned teacher can complete this lesson")

        if (lesson[LessonTable.status] != LessonStatus.IN_PROGRESS)
            throw BadRequestException("Only in-progress lessons can be completed")

        val now = OffsetDateTime.now()

        LessonDocumentTable.update({ LessonDocumentTable.lessonId eq lessonId }) {
            if (request.teacherNotes != null) it[teacherNotes] = request.teacherNotes
            if (request.teacherWentWell != null) it[teacherWentWell] = request.teacherWentWell
            if (request.teacherWorkingOn != null) it[teacherWorkingOn] = request.teacherWorkingOn
            it[updatedAt] = now
        }

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[status] = LessonStatus.COMPLETED
            it[endedAt] = now
            it[updatedBy] = principal.id
            it[updatedAt] = now
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.LESSON_COMPLETED
            it[actorId] = principal.id
            it[oldStatus] = lesson[LessonTable.status]
            it[newStatus] = LessonStatus.COMPLETED
        }

        for (studentId in support.getStudentIds(lessonId)) {
            notificationService.createNotification(
                userId = studentId,
                actorId = principal.id,
                type = NotificationType.LESSON_COMPLETED,
                referenceId = lessonId,
            )
        }

        LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId eq lessonId }
            .single()
            .let(support::toDocumentResponse)
    }

    fun getVideoAccess(lessonId: UUID, principal: UserPrincipal): VideoAccess = transaction {
        val lesson = support.findLesson(lessonId)
        support.requireLessonParticipant(lessonId, lesson, principal)

        val status = lesson[LessonTable.status]
        val allowed = when {
            status == LessonStatus.IN_PROGRESS -> true
            status == LessonStatus.CONFIRMED && principal.role != UserRole.STUDENT -> true
            status == LessonStatus.CONFIRMED && principal.role == UserRole.STUDENT -> {
                val minutesToStart = Duration.between(
                    OffsetDateTime.now(),
                    lesson[LessonTable.scheduledAt],
                ).toMinutes()
                minutesToStart <= EARLY_JOIN_MINUTES
            }
            else -> false
        }
        if (!allowed)
            throw BadRequestException("Video room is not yet available for this lesson")

        mintVideoAccess(lessonId, principal)
    }

    private fun mintVideoAccess(lessonId: UUID, principal: UserPrincipal): VideoAccess {
        val firstName = UserTable.selectAll()
            .where { UserTable.id eq principal.id }
            .singleOrNull()
            ?.get(UserTable.firstName)
            ?: throw NotFoundException("User not found")

        val role = when (principal.role) {
            UserRole.TEACHER, UserRole.ADMIN -> VideoParticipantRole.TEACHER
            UserRole.STUDENT -> VideoParticipantRole.STUDENT
        }

        return videoTokenService.mintToken(
            roomName = "lesson-$lessonId",
            identity = principal.id,
            displayName = firstName,
            role = role,
            lessonId = lessonId,
        )
    }
}

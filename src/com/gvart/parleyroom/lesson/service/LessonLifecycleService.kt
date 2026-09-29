package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.service.LearningActivityRecorder
import com.gvart.parleyroom.availability.service.AvailabilityValidator
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonEventTable
import com.gvart.parleyroom.lesson.data.LessonEventType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class LessonLifecycleService(
    private val notificationService: NotificationService,
    private val videoTokenService: VideoTokenService,
    private val support: LessonSupport,
    private val availabilityValidator: AvailabilityValidator,
) {

    companion object {
        /** Students may dial in this many minutes before scheduledAt; teachers have no such gate. */
        const val EARLY_JOIN_MINUTES: Long = 10

        /** How long the canceller can undo a cancellation; the other side is notified after it. */
        val UNCANCEL_WINDOW: Duration = Duration.ofSeconds(10)
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

        val groupId = request.groupId?.let(UUID::fromString)?.also { id ->
            val group = GroupTable.findByIdOrThrow(id, "Group")
            if (group[GroupTable.teacherId].value != teacherId)
                throw ForbiddenException("Group belongs to another teacher")
        }

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
            it[LessonTable.groupId] = groupId
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
            .let { support.toResponse(it, principal) }
    }

    fun acceptLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLesson(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the assigned teacher can accept lesson requests")

        if (lesson[LessonTable.status] != LessonStatus.REQUEST)
            throw BadRequestException("Only lessons with REQUEST status can be accepted", code = "LESSON_INVALID_STATE")

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
            .let { support.toResponse(it, principal) }
    }

    fun cancelLesson(lessonId: UUID, request: CancelLessonRequest, principal: UserPrincipal): LessonResponse {
        val (response, priorStatus) = doCancelLesson(lessonId, request, principal)
        // Only tear the LiveKit room down if the lesson was actually live —
        // cancelling a REQUEST/CONFIRMED lesson never opened one.
        if (priorStatus == LessonStatus.IN_PROGRESS) {
            videoTokenService.deleteRoom("lesson-$lessonId")
        }
        return response
    }

    private fun doCancelLesson(
        lessonId: UUID,
        request: CancelLessonRequest,
        principal: UserPrincipal,
    ): Pair<LessonResponse, LessonStatus> = transaction {
        val lesson = support.findLesson(lessonId)
        val currentStatus = lesson[LessonTable.status]

        if (currentStatus == LessonStatus.COMPLETED || currentStatus == LessonStatus.CANCELLED)
            throw BadRequestException("Cannot cancel a ${currentStatus.name.lowercase()} lesson", code = "LESSON_INVALID_STATE")

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
            it[cancelReason] = request.reason
            it[cancelledBy] = principal.id
            it[cancelledAt] = now
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
                deliverAfter = now.plus(UNCANCEL_WINDOW),
            )
        }

        val response = LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .single()
            .let { support.toResponse(it, principal) }
        response to currentStatus
    }

    /**
     * Undoes a cancellation: only the canceller, only within [UNCANCEL_WINDOW]. Restores the
     * status the lesson had before and drops the not-yet-delivered cancel notifications.
     * Reschedule proposals resolved by the cancel stay resolved.
     */
    fun uncancelLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLessonForUpdate(lessonId)

        if (lesson[LessonTable.status] != LessonStatus.CANCELLED)
            throw BadRequestException("Only cancelled lessons can be restored", code = "LESSON_INVALID_STATE")

        if (lesson[LessonTable.cancelledBy]?.value != principal.id)
            throw ForbiddenException("Only whoever cancelled the lesson can undo it")

        val now = OffsetDateTime.now()
        val cancelledAt = lesson[LessonTable.cancelledAt]
        if (cancelledAt == null || cancelledAt.plus(UNCANCEL_WINDOW).isBefore(now))
            throw ConflictException("The undo window for this cancellation has passed", code = "UNCANCEL_WINDOW_EXPIRED")

        val restoredStatus = LessonEventTable.selectAll()
            .where { (LessonEventTable.lessonId eq lessonId) and (LessonEventTable.eventType eq LessonEventType.LESSON_CANCELLED) }
            .orderBy(LessonEventTable.createdAt, SortOrder.DESC)
            .first()[LessonEventTable.oldStatus]!!

        // The slot may have been booked while the lesson was cancelled.
        support.checkTeacherOverlap(
            lesson[LessonTable.teacherId].value,
            lesson[LessonTable.scheduledAt],
            lesson[LessonTable.durationMinutes],
            excludeLessonId = lessonId,
        )

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[status] = restoredStatus
            it[cancelReason] = null
            it[cancelledBy] = null
            it[LessonTable.cancelledAt] = null
            it[updatedBy] = principal.id
            it[updatedAt] = now
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.STATUS_CHANGE
            it[actorId] = principal.id
            it[oldStatus] = LessonStatus.CANCELLED
            it[newStatus] = restoredStatus
        }

        notificationService.withdrawUndelivered(lessonId, NotificationType.LESSON_CANCELLED)

        support.toResponse(support.findLesson(lessonId), principal)
    }

    fun startLesson(lessonId: UUID, principal: UserPrincipal): StartLessonResponse = transaction {
        val lesson = support.findLesson(lessonId)

        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers or admins can start a lesson")

        if (lesson[LessonTable.teacherId].value != principal.id && principal.role != UserRole.ADMIN)
            throw ForbiddenException("Only the assigned teacher can start this lesson")

        if (lesson[LessonTable.status] != LessonStatus.CONFIRMED)
            throw BadRequestException("Only confirmed lessons can be started", code = "LESSON_INVALID_STATE")

        if (lesson[LessonTable.startedAt] != null)
            throw ConflictException("Lesson has already been started", code = "LESSON_ALREADY_STARTED")

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

        StartLessonResponse(videoRoom = mintVideoAccess(lessonId, principal))
    }

    fun completeLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse {
        val response = doCompleteLesson(lessonId, principal)
        // Kick everyone out of the LiveKit room so students don't linger in
        // a dead call after the teacher wraps. `deleteRoom` is already async
        // and internally catches failures — failure here doesn't undo the
        // completion.
        videoTokenService.deleteRoom("lesson-$lessonId")
        return response
    }

    private fun doCompleteLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLesson(lessonId)

        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers or admins can complete a lesson")

        if (lesson[LessonTable.teacherId].value != principal.id && principal.role != UserRole.ADMIN)
            throw ForbiddenException("Only the assigned teacher can complete this lesson")

        if (lesson[LessonTable.status] != LessonStatus.IN_PROGRESS)
            throw BadRequestException("Only in-progress lessons can be completed", code = "LESSON_INVALID_STATE")

        val now = OffsetDateTime.now()

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
            LearningActivityRecorder.record(studentId, ActivityKind.LESSON_COMPLETED, lessonId)
            notificationService.createNotification(
                userId = studentId,
                actorId = principal.id,
                type = NotificationType.LESSON_COMPLETED,
                referenceId = lessonId,
            )
        }

        support.toResponse(support.findLesson(lessonId), principal)
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
            throw BadRequestException("Video room is not yet available for this lesson", code = "VIDEO_ROOM_NOT_READY")

        mintVideoAccess(lessonId, principal)
    }

    private fun mintVideoAccess(lessonId: UUID, principal: UserPrincipal): VideoAccess {
        val firstName = UserTable.selectAll()
            .where { UserTable.id eq principal.id }
            .singleOrNull()
            ?.get(UserTable.firstName)
            ?: throw NotFoundException("User not found", code = "USER_NOT_FOUND")

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

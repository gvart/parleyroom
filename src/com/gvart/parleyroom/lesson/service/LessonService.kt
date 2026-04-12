package com.gvart.parleyroom.lesson.service

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
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.lesson.transfer.CancelLessonRequest
import com.gvart.parleyroom.lesson.transfer.CompleteLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonDocumentResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.LessonStudentResponse
import com.gvart.parleyroom.lesson.transfer.PendingRescheduleResponse
import com.gvart.parleyroom.lesson.transfer.ReflectLessonRequest
import com.gvart.parleyroom.lesson.transfer.RescheduleLessonRequest
import com.gvart.parleyroom.lesson.transfer.StartLessonResponse
import com.gvart.parleyroom.lesson.transfer.SyncLessonDocumentRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.video.service.VideoTokenService
import com.gvart.parleyroom.video.transfer.VideoAccess
import com.gvart.parleyroom.video.transfer.VideoParticipantRole
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.GreaterOp
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.QueryParameter
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class LessonService(
    private val notificationService: NotificationService,
    private val videoTokenService: VideoTokenService,
) {

    fun getLessons(
        principal: UserPrincipal,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
    ): List<LessonResponse> = transaction {
        val baseQuery = when (principal.role) {
            UserRole.ADMIN -> LessonTable.selectAll()
            UserRole.TEACHER -> LessonTable.selectAll()
                .where { LessonTable.teacherId eq principal.id }
            UserRole.STUDENT -> LessonTable.join(
                LessonStudentTable, JoinType.INNER,
                LessonTable.id, LessonStudentTable.lessonId
            ).selectAll().where {
                (LessonStudentTable.studentId eq principal.id) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
        }

        if (from != null) baseQuery.andWhere { LessonTable.scheduledAt greaterEq from }
        if (to != null) baseQuery.andWhere { LessonTable.scheduledAt lessEq to }

        baseQuery.map(::toResponse)
    }

    fun getLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = findLesson(lessonId)
        requireLessonParticipant(lessonId, lesson, principal)
        toResponse(lesson)
    }

    fun createLesson(request: CreateLessonRequest, principal: UserPrincipal): LessonResponse = transaction {
        val teacherId = UUID.fromString(request.teacherId)
        val studentIds = request.studentIds.map { UUID.fromString(it) }

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

        val now = OffsetDateTime.now()
        val scheduledAt = OffsetDateTime.parse(request.scheduledAt)

        checkTeacherOverlap(teacherId, scheduledAt, request.durationMinutes)

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
            it[LessonTable.createdAt] = now
            it[LessonTable.updatedAt] = now
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
            it[createdAt] = now
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
            .let(::toResponse)
    }

    fun acceptLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = findLesson(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the assigned teacher can accept lesson requests")

        if (lesson[LessonTable.status] != LessonStatus.REQUEST)
            throw BadRequestException("Only lessons with REQUEST status can be accepted")

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[status] = LessonStatus.CONFIRMED
            it[updatedBy] = principal.id
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.STATUS_CHANGE
            it[actorId] = principal.id
            it[oldStatus] = LessonStatus.REQUEST
            it[newStatus] = LessonStatus.CONFIRMED
            it[createdAt] = OffsetDateTime.now()
        }

        val studentIds = getStudentIds(lessonId)
        for (studentId in studentIds) {
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
            .let(::toResponse)
    }

    fun cancelLesson(lessonId: UUID, request: CancelLessonRequest, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = findLesson(lessonId)
        val currentStatus = lesson[LessonTable.status]

        if (currentStatus == LessonStatus.COMPLETED || currentStatus == LessonStatus.CANCELLED)
            throw BadRequestException("Cannot cancel a ${currentStatus.name.lowercase()} lesson")

        requireLessonParticipant(lessonId, lesson, principal)

        val now = OffsetDateTime.now()

        // Resolve any pending reschedule
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
            it[createdAt] = now
        }

        val otherParticipants = getOtherParticipants(lessonId, lesson, principal.id)
        for (userId in otherParticipants) {
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
            .let(::toResponse)
    }

    fun joinLesson(lessonId: UUID, principal: UserPrincipal) = transaction {
        val lesson = findLesson(lessonId)

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
            // REJECTED → allow re-request by updating status
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
        val lesson = findLesson(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the teacher can accept join requests")

        val entry = findStudentEntry(lessonId, studentId)
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
        val lesson = findLesson(lessonId)

        if (lesson[LessonTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the teacher can reject join requests")

        val entry = findStudentEntry(lessonId, studentId)
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

    fun rescheduleLesson(lessonId: UUID, request: RescheduleLessonRequest, principal: UserPrincipal) = transaction {
        val lesson = findLesson(lessonId)

        if (lesson[LessonTable.status] != LessonStatus.CONFIRMED)
            throw BadRequestException("Only confirmed lessons can be rescheduled")

        requireLessonParticipant(lessonId, lesson, principal)

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
            it[newScheduledAt] = OffsetDateTime.parse(request.newScheduledAt)
            it[note] = request.note
            it[createdAt] = OffsetDateTime.now()
        }

        val otherParticipants = getOtherParticipants(lessonId, lesson, principal.id)
        for (userId in otherParticipants) {
            notificationService.createNotification(
                userId = userId,
                actorId = principal.id,
                type = NotificationType.RESCHEDULE_REQUESTED,
                referenceId = lessonId,
            )
        }
    }

    fun acceptReschedule(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = findLesson(lessonId)
        val pendingEvent = findPendingReschedule(lessonId)

        if (pendingEvent[LessonEventTable.actorId].value == principal.id)
            throw ForbiddenException("Cannot accept your own reschedule request")

        requireLessonParticipant(lessonId, lesson, principal)

        val newScheduledAt = pendingEvent[LessonEventTable.newScheduledAt]!!

        checkTeacherOverlap(
            lesson[LessonTable.teacherId].value,
            newScheduledAt,
            lesson[LessonTable.durationMinutes],
            excludeLessonId = lessonId
        )

        LessonEventTable.update({ LessonEventTable.id eq pendingEvent[LessonEventTable.id] }) {
            it[resolved] = true
        }

        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[scheduledAt] = newScheduledAt
            it[updatedBy] = principal.id
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.RESCHEDULE_ACCEPTED
            it[actorId] = principal.id
            it[oldScheduledAt] = lesson[LessonTable.scheduledAt]
            it[LessonEventTable.newScheduledAt] = newScheduledAt
            it[createdAt] = OffsetDateTime.now()
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
            .let(::toResponse)
    }

    fun rejectReschedule(lessonId: UUID, principal: UserPrincipal) = transaction {
        val lesson = findLesson(lessonId)
        val pendingEvent = findPendingReschedule(lessonId)

        if (pendingEvent[LessonEventTable.actorId].value == principal.id)
            throw ForbiddenException("Cannot reject your own reschedule request")

        requireLessonParticipant(lessonId, lesson, principal)

        LessonEventTable.update({ LessonEventTable.id eq pendingEvent[LessonEventTable.id] }) {
            it[resolved] = true
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.RESCHEDULE_REJECTED
            it[actorId] = principal.id
            it[oldScheduledAt] = pendingEvent[LessonEventTable.oldScheduledAt]
            it[newScheduledAt] = pendingEvent[LessonEventTable.newScheduledAt]
            it[createdAt] = OffsetDateTime.now()
        }

        val requesterId = pendingEvent[LessonEventTable.actorId].value
        notificationService.createNotification(
            userId = requesterId,
            actorId = principal.id,
            type = NotificationType.RESCHEDULE_REJECTED,
            referenceId = lessonId,
        )
    }

    fun startLesson(lessonId: UUID, principal: UserPrincipal): StartLessonResponse = transaction {
        val lesson = findLesson(lessonId)

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

        val docId = LessonDocumentTable.insertAndGetId {
            it[LessonDocumentTable.lessonId] = lessonId
            it[createdAt] = now
            it[updatedAt] = now
            it[teacherNotes] = ""
            it[studentNotes] = ""
        }

        LessonEventTable.insert {
            it[LessonEventTable.lessonId] = lessonId
            it[eventType] = LessonEventType.LESSON_STARTED
            it[actorId] = principal.id
            it[oldStatus] = LessonStatus.CONFIRMED
            it[newStatus] = LessonStatus.IN_PROGRESS
            it[createdAt] = now
        }

        val studentIds = getStudentIds(lessonId)
        for (studentId in studentIds) {
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
            .let(::toDocumentResponse)

        val videoRoom = mintVideoAccess(lessonId, principal)
        StartLessonResponse(document = document, videoRoom = videoRoom)
    }

    fun getVideoAccess(lessonId: UUID, principal: UserPrincipal): VideoAccess = transaction {
        val lesson = findLesson(lessonId)
        requireLessonParticipant(lessonId, lesson, principal)

        if (lesson[LessonTable.status] != LessonStatus.IN_PROGRESS)
            throw BadRequestException("Video room is only available while the lesson is in progress")

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

    fun syncDocument(lessonId: UUID, request: SyncLessonDocumentRequest, principal: UserPrincipal): LessonDocumentResponse = transaction {
        val lesson = findLesson(lessonId)
        requireLessonParticipant(lessonId, lesson, principal)

        if (LessonDocumentTable.selectAll()
                .where { LessonDocumentTable.lessonId eq lessonId }
                .empty()
        ) throw BadRequestException("Lesson has not been started yet")

        if (lesson[LessonTable.status] != LessonStatus.IN_PROGRESS)
            throw BadRequestException("Can only sync document for an in-progress lesson")

        if (request.notes != null) {
            val now = OffsetDateTime.now()
            LessonDocumentTable.update({ LessonDocumentTable.lessonId eq lessonId }) {
                when (principal.role) {
                    UserRole.TEACHER, UserRole.ADMIN -> it[teacherNotes] = request.notes
                    UserRole.STUDENT -> it[studentNotes] = request.notes
                }
                it[updatedAt] = now
            }
        }

        LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId eq lessonId }
            .single()
            .let(::toDocumentResponse)
    }

    fun completeLesson(lessonId: UUID, request: CompleteLessonRequest, principal: UserPrincipal): LessonDocumentResponse = transaction {
        val lesson = findLesson(lessonId)

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
            it[createdAt] = now
        }

        val studentIds = getStudentIds(lessonId)
        for (studentId in studentIds) {
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
            .let(::toDocumentResponse)
    }

    fun reflectOnLesson(lessonId: UUID, request: ReflectLessonRequest, principal: UserPrincipal): LessonDocumentResponse = transaction {
        val lesson = findLesson(lessonId)
        requireLessonParticipant(lessonId, lesson, principal)

        if (principal.role != UserRole.STUDENT)
            throw ForbiddenException("Only students can submit reflections")

        if (lesson[LessonTable.startedAt] == null)
            throw BadRequestException("Lesson has not been started yet")

        val now = OffsetDateTime.now()

        LessonDocumentTable.update({ LessonDocumentTable.lessonId eq lessonId }) {
            if (request.studentReflection != null) it[studentReflection] = request.studentReflection
            if (request.studentHardToday != null) it[studentHardToday] = request.studentHardToday
            it[updatedAt] = now
        }

        LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId eq lessonId }
            .single()
            .let(::toDocumentResponse)
    }

    private fun getStudentIds(lessonId: UUID): List<UUID> =
        LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .map { it[LessonStudentTable.studentId].value }

    private fun getOtherParticipants(lessonId: UUID, lesson: ResultRow, excludeUserId: UUID): List<UUID> {
        val teacherId = lesson[LessonTable.teacherId].value
        val studentIds = getStudentIds(lessonId)
        return (studentIds + teacherId).filter { it != excludeUserId }
    }

    private fun checkTeacherOverlap(
        teacherId: UUID,
        scheduledAt: OffsetDateTime,
        durationMinutes: Int,
        excludeLessonId: UUID? = null
    ) {
        val newEnd = scheduledAt.plusMinutes(durationMinutes.toLong())

        val existingLessonEnd = object : Expression<OffsetDateTime>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                LessonTable.scheduledAt.toQueryBuilder(queryBuilder)
                queryBuilder.append(" + (")
                LessonTable.durationMinutes.toQueryBuilder(queryBuilder)
                queryBuilder.append(" * INTERVAL '1 minute')")
            }
        }

        val newStartParam = QueryParameter(scheduledAt, LessonTable.scheduledAt.columnType)

        val query = LessonTable.selectAll().where {
            (LessonTable.teacherId eq teacherId) and
                    (LessonTable.status neq LessonStatus.CANCELLED) and
                    (LessonTable.scheduledAt less newEnd) and
                    GreaterOp(existingLessonEnd, newStartParam)
        }

        if (excludeLessonId != null) {
            query.andWhere { LessonTable.id neq excludeLessonId }
        }

        if (!query.empty()) {
            throw ConflictException("Teacher already has a lesson scheduled during this time")
        }
    }

    private fun findLesson(lessonId: UUID): ResultRow =
        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .singleOrNull() ?: throw NotFoundException("Lesson not found")

    private fun findPendingReschedule(lessonId: UUID): ResultRow =
        LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId eq lessonId) and
                        (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                        (LessonEventTable.resolved eq false)
            }
            .singleOrNull() ?: throw NotFoundException("No pending reschedule found")

    private fun findStudentEntry(lessonId: UUID, studentId: UUID): ResultRow =
        LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq studentId)
            }
            .singleOrNull() ?: throw NotFoundException("Student not found in this lesson")

    private fun requireLessonParticipant(lessonId: UUID, lesson: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return

        val isTeacher = lesson[LessonTable.teacherId].value == principal.id
        val isStudent = LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq principal.id) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .empty().not()

        if (!isTeacher && !isStudent)
            throw ForbiddenException("Only participants of this lesson can perform this action")
    }

    private fun toDocumentResponse(row: ResultRow) = LessonDocumentResponse(
        id = row[LessonDocumentTable.id].value.toString(),
        lessonId = row[LessonDocumentTable.lessonId].value.toString(),
        teacherNotes = row[LessonDocumentTable.teacherNotes],
        studentNotes = row[LessonDocumentTable.studentNotes],
        teacherWentWell = row[LessonDocumentTable.teacherWentWell],
        teacherWorkingOn = row[LessonDocumentTable.teacherWorkingOn],
        studentReflection = row[LessonDocumentTable.studentReflection],
        studentHardToday = row[LessonDocumentTable.studentHardToday],
        createdAt = row[LessonDocumentTable.createdAt].toString(),
        updatedAt = row[LessonDocumentTable.updatedAt].toString(),
    )

    private fun toResponse(row: ResultRow): LessonResponse {
        val lessonId = row[LessonTable.id].value

        val students = LessonStudentTable
            .join(UserTable, JoinType.INNER, LessonStudentTable.studentId, UserTable.id)
            .selectAll()
            .where { LessonStudentTable.lessonId eq lessonId }
            .map { studentRow ->
                LessonStudentResponse(
                    id = studentRow[UserTable.id].value.toString(),
                    firstName = studentRow[UserTable.firstName],
                    lastName = studentRow[UserTable.lastName],
                    status = studentRow[LessonStudentTable.status].name,
                )
            }

        val doc = LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId eq lessonId }
            .singleOrNull()

        val pendingReschedule = LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId eq lessonId) and
                        (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                        (LessonEventTable.resolved eq false)
            }
            .singleOrNull()
            ?.let {
                PendingRescheduleResponse(
                    newScheduledAt = it[LessonEventTable.newScheduledAt]!!.toString(),
                    note = it[LessonEventTable.note],
                    requestedBy = it[LessonEventTable.actorId].value.toString(),
                )
            }

        return LessonResponse(
            id = lessonId.toString(),
            title = row[LessonTable.title],
            type = row[LessonTable.type],
            scheduledAt = row[LessonTable.scheduledAt].toString(),
            durationMinutes = row[LessonTable.durationMinutes],
            teacherId = row[LessonTable.teacherId].value.toString(),
            status = row[LessonTable.status],
            topic = row[LessonTable.topic],
            level = row[LessonTable.level],
            maxParticipants = row[LessonTable.maxParticipants],
            students = students,
            startedAt = row[LessonTable.startedAt]?.toString(),
            pendingReschedule = pendingReschedule,
            teacherNotes = doc?.get(LessonDocumentTable.teacherNotes),
            studentNotes = doc?.get(LessonDocumentTable.studentNotes),
            teacherWentWell = doc?.get(LessonDocumentTable.teacherWentWell),
            teacherWorkingOn = doc?.get(LessonDocumentTable.teacherWorkingOn),
            studentReflection = doc?.get(LessonDocumentTable.studentReflection),
            studentHardToday = doc?.get(LessonDocumentTable.studentHardToday),
            createdBy = row[LessonTable.createdBy].value.toString(),
            updatedBy = row[LessonTable.updatedBy]?.value?.toString(),
            createdAt = row[LessonTable.createdAt].toString(),
            updatedAt = row[LessonTable.updatedAt].toString(),
        )
    }
}
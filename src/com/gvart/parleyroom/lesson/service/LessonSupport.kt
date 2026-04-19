package com.gvart.parleyroom.lesson.service

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
import com.gvart.parleyroom.lesson.transfer.LessonDocumentResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.LessonStudentResponse
import com.gvart.parleyroom.lesson.transfer.PendingRescheduleResponse
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.GreaterOp
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.QueryBuilder
import org.jetbrains.exposed.sql.QueryParameter
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import java.time.OffsetDateTime
import java.util.UUID

class LessonSupport {

    fun findLesson(lessonId: UUID): ResultRow =
        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .singleOrNull() ?: throw NotFoundException("Lesson not found")

    fun findLessonForUpdate(lessonId: UUID): ResultRow =
        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .forUpdate()
            .singleOrNull() ?: throw NotFoundException("Lesson not found")

    fun findPendingReschedule(lessonId: UUID): ResultRow =
        LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId eq lessonId) and
                        (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                        (LessonEventTable.resolved eq false)
            }
            .singleOrNull() ?: throw NotFoundException("No pending reschedule found")

    fun findStudentEntry(lessonId: UUID, studentId: UUID): ResultRow =
        LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq studentId)
            }
            .singleOrNull() ?: throw NotFoundException("Student not found in this lesson")

    fun requireLessonParticipant(lessonId: UUID, lesson: ResultRow, principal: UserPrincipal) {
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

    fun checkTeacherOverlap(
        teacherId: UUID,
        scheduledAt: OffsetDateTime,
        durationMinutes: Int,
        excludeLessonId: UUID? = null,
        bufferMinutes: Int = 0,
    ) {
        // Hard overlap first (buffer=0). A real collision takes precedence over a
        // buffer-zone conflict so the error code reflects the actual cause.
        if (hasOverlap(teacherId, scheduledAt, durationMinutes, excludeLessonId, bufferMinutes = 0)) {
            throw ConflictException(
                "Teacher already has a lesson scheduled during this time",
                code = "AVAILABILITY_OVERLAP",
            )
        }
        if (bufferMinutes > 0 &&
            hasOverlap(teacherId, scheduledAt, durationMinutes, excludeLessonId, bufferMinutes)
        ) {
            throw ConflictException(
                "Booking is too close to another lesson (buffer $bufferMinutes min)",
                code = "AVAILABILITY_BUFFER_CONFLICT",
            )
        }
    }

    private fun hasOverlap(
        teacherId: UUID,
        scheduledAt: OffsetDateTime,
        durationMinutes: Int,
        excludeLessonId: UUID?,
        bufferMinutes: Int,
    ): Boolean {
        val newStart = scheduledAt.minusMinutes(bufferMinutes.toLong())
        val newEnd = scheduledAt.plusMinutes(durationMinutes.toLong()).plusMinutes(bufferMinutes.toLong())

        val existingLessonEnd = object : Expression<OffsetDateTime>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                LessonTable.scheduledAt.toQueryBuilder(queryBuilder)
                queryBuilder.append(" + (")
                LessonTable.durationMinutes.toQueryBuilder(queryBuilder)
                queryBuilder.append(" * INTERVAL '1 minute')")
            }
        }

        val newStartParam = QueryParameter(newStart, LessonTable.scheduledAt.columnType)

        val query = LessonTable.selectAll().where {
            (LessonTable.teacherId eq teacherId) and
                    (LessonTable.status neq LessonStatus.CANCELLED) and
                    (LessonTable.scheduledAt less newEnd) and
                    GreaterOp(existingLessonEnd, newStartParam)
        }

        if (excludeLessonId != null) {
            query.andWhere { LessonTable.id neq excludeLessonId }
        }

        return !query.empty()
    }

    fun getStudentIds(lessonId: UUID): List<UUID> =
        LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .map { it[LessonStudentTable.studentId].value }

    fun getOtherParticipants(lessonId: UUID, lesson: ResultRow, excludeUserId: UUID): List<UUID> {
        val teacherId = lesson[LessonTable.teacherId].value
        val studentIds = getStudentIds(lessonId)
        return (studentIds + teacherId).filter { it != excludeUserId }
    }

    fun toResponse(row: ResultRow): LessonResponse = toResponses(listOf(row)).single()

    fun toResponses(rows: List<ResultRow>): List<LessonResponse> {
        if (rows.isEmpty()) return emptyList()
        val lessonIds = rows.map { it[LessonTable.id].value }

        val studentsByLesson: Map<UUID, List<LessonStudentResponse>> = LessonStudentTable
            .join(UserTable, JoinType.INNER, LessonStudentTable.studentId, UserTable.id)
            .selectAll()
            .where { LessonStudentTable.lessonId inList lessonIds }
            .groupBy({ it[LessonStudentTable.lessonId].value }) { studentRow ->
                LessonStudentResponse(
                    id = studentRow[UserTable.id].value.toString(),
                    firstName = studentRow[UserTable.firstName],
                    lastName = studentRow[UserTable.lastName],
                    status = studentRow[LessonStudentTable.status].name,
                )
            }

        val docByLesson: Map<UUID, ResultRow> = LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId inList lessonIds }
            .associateBy { it[LessonDocumentTable.lessonId].value }

        val pendingByLesson: Map<UUID, PendingRescheduleResponse> = LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId inList lessonIds) and
                        (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                        (LessonEventTable.resolved eq false)
            }
            .associate { ev ->
                ev[LessonEventTable.lessonId].value to PendingRescheduleResponse(
                    newScheduledAt = ev[LessonEventTable.newScheduledAt]!!,
                    note = ev[LessonEventTable.note],
                    requestedBy = ev[LessonEventTable.actorId].value.toString(),
                )
            }

        return rows.map { row ->
            val lessonId = row[LessonTable.id].value
            val doc = docByLesson[lessonId]
            LessonResponse(
                id = lessonId.toString(),
                title = row[LessonTable.title],
                type = row[LessonTable.type],
                scheduledAt = row[LessonTable.scheduledAt],
                durationMinutes = row[LessonTable.durationMinutes],
                teacherId = row[LessonTable.teacherId].value.toString(),
                status = row[LessonTable.status],
                topic = row[LessonTable.topic],
                level = row[LessonTable.level],
                maxParticipants = row[LessonTable.maxParticipants],
                students = studentsByLesson[lessonId] ?: emptyList(),
                startedAt = row[LessonTable.startedAt],
                pendingReschedule = pendingByLesson[lessonId],
                teacherNotes = doc?.get(LessonDocumentTable.teacherNotes),
                studentNotes = doc?.get(LessonDocumentTable.studentNotes),
                teacherWentWell = doc?.get(LessonDocumentTable.teacherWentWell),
                teacherWorkingOn = doc?.get(LessonDocumentTable.teacherWorkingOn),
                studentReflection = doc?.get(LessonDocumentTable.studentReflection),
                studentHardToday = doc?.get(LessonDocumentTable.studentHardToday),
                createdBy = row[LessonTable.createdBy].value.toString(),
                updatedBy = row[LessonTable.updatedBy]?.value?.toString(),
                createdAt = row[LessonTable.createdAt],
                updatedAt = row[LessonTable.updatedAt],
            )
        }
    }

    fun toDocumentResponse(row: ResultRow) = LessonDocumentResponse(
        id = row[LessonDocumentTable.id].value.toString(),
        lessonId = row[LessonDocumentTable.lessonId].value.toString(),
        teacherNotes = row[LessonDocumentTable.teacherNotes],
        studentNotes = row[LessonDocumentTable.studentNotes],
        teacherWentWell = row[LessonDocumentTable.teacherWentWell],
        teacherWorkingOn = row[LessonDocumentTable.teacherWorkingOn],
        studentReflection = row[LessonDocumentTable.studentReflection],
        studentHardToday = row[LessonDocumentTable.studentHardToday],
        createdAt = row[LessonDocumentTable.createdAt],
        updatedAt = row[LessonDocumentTable.updatedAt],
    )
}

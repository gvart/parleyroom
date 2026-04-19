package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.PublicCalendarResponse
import com.gvart.parleyroom.lesson.transfer.PublicLesson
import com.gvart.parleyroom.lesson.transfer.PublicTeacher
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class LessonService(
    private val support: LessonSupport,
) {

    fun getLessons(
        principal: UserPrincipal,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        page: PageRequest,
    ): LessonPageResponse = transaction {
        val baseQuery = when (principal.role) {
            UserRole.ADMIN -> LessonTable.selectAll()
            UserRole.TEACHER -> LessonTable.selectAll()
                .where { LessonTable.teacherId eq principal.id }
            UserRole.STUDENT -> {
                // Lessons where the student is a confirmed participant.
                val myLessonIds = LessonStudentTable
                    .select(LessonStudentTable.lessonId)
                    .where {
                        (LessonStudentTable.studentId eq principal.id) and
                                (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                    }
                    .map { it[LessonStudentTable.lessonId] }
                    .toSet()

                // Teachers the student is actively linked to. Every non-cancelled
                // lesson from these teachers is surfaced so students can (a) join
                // open clubs and (b) see when their teacher is busy with another
                // learner. Privacy scrubbing for other students' 1:1 slots
                // happens in the response mapping below.
                val myTeacherIds = TeacherStudentTable
                    .select(TeacherStudentTable.teacherId)
                    .where {
                        (TeacherStudentTable.studentId eq principal.id) and
                                (TeacherStudentTable.status eq UserStatus.ACTIVE)
                    }
                    .map { it[TeacherStudentTable.teacherId] }
                    .toSet()

                LessonTable.selectAll().where {
                    (LessonTable.id inList myLessonIds) or
                            (
                                (LessonTable.teacherId inList myTeacherIds) and
                                        (LessonTable.status inList listOf(
                                            LessonStatus.CONFIRMED,
                                            LessonStatus.IN_PROGRESS,
                                            LessonStatus.COMPLETED,
                                        ))
                            )
                }
            }
        }

        if (from != null) baseQuery.andWhere { LessonTable.scheduledAt greaterEq from }
        if (to != null) baseQuery.andWhere { LessonTable.scheduledAt lessEq to }

        val total = baseQuery.count()
        val rows = baseQuery
            .limit(page.pageSize)
            .offset(page.offset)
            .toList()

        val lessons = support.toResponses(rows).let { responses ->
            if (principal.role != UserRole.STUDENT) responses
            else responses.map { r -> scrubForStudent(r, principal.id) }
        }

        LessonPageResponse(
            lessons = lessons,
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    /**
     * When a student sees a lesson they are not a participant of, strip every
     * field that would leak another learner's information — topic, title,
     * roster, notes, documents. A group club they haven't joined yet keeps
     * its title + topic (those clubs are public-to-the-cohort by design); a
     * 1:1 slot with another student collapses to a plain "busy" block.
     */
    private fun scrubForStudent(response: LessonResponse, studentId: UUID): LessonResponse {
        val studentIdStr = studentId.toString()
        val isParticipant = response.students.any { it.id == studentIdStr }
        if (isParticipant) return response

        val isOneOnOne = response.type == LessonType.ONE_ON_ONE
        return if (isOneOnOne) {
            response.copy(
                title = "",
                topic = "",
                level = null,
                students = emptyList(),
                maxParticipants = null,
                pendingReschedule = null,
                teacherNotes = null,
                studentNotes = null,
                teacherWentWell = null,
                teacherWorkingOn = null,
                studentReflection = null,
                studentHardToday = null,
            )
        } else {
            // Group club — show title/topic/spots (joinable) but hide private docs.
            response.copy(
                students = emptyList(),
                teacherNotes = null,
                studentNotes = null,
                teacherWentWell = null,
                teacherWorkingOn = null,
                studentReflection = null,
                studentHardToday = null,
            )
        }
    }

    fun getLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLesson(lessonId)
        support.requireLessonParticipant(lessonId, lesson, principal)
        support.toResponse(lesson)
    }

    /**
     * Unauthenticated teacher-calendar view. Returns the teacher's public
     * first+last name and every non-cancelled lesson in the optional date
     * window, with private data scrubbed: 1:1 slots collapse to busy blocks,
     * group clubs keep title/topic so the calendar is discoverable.
     */
    fun getPublicCalendar(
        teacherId: UUID,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
    ): PublicCalendarResponse = transaction {
        val teacherRow = UserTable.selectAll()
            .where { (UserTable.id eq teacherId) and (UserTable.role eq UserRole.TEACHER) }
            .singleOrNull() ?: throw NotFoundException("Teacher not found")

        val query = LessonTable.selectAll().where {
            (LessonTable.teacherId eq teacherId) and
                    (LessonTable.status inList listOf(
                        LessonStatus.CONFIRMED,
                        LessonStatus.IN_PROGRESS,
                        LessonStatus.COMPLETED,
                    ))
        }
        if (from != null) query.andWhere { LessonTable.scheduledAt greaterEq from }
        if (to != null) query.andWhere { LessonTable.scheduledAt lessEq to }

        val rows = query.toList()
        val lessonIds = rows.map { it[LessonTable.id].value }

        val participantCountByLesson: Map<UUID, Long> = LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId inList lessonIds) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .groupBy { it[LessonStudentTable.lessonId].value }
            .mapValues { (_, v) -> v.size.toLong() }

        val lessons = rows.map { row ->
            val lessonId = row[LessonTable.id].value
            val type = row[LessonTable.type]
            val isGroup = type != LessonType.ONE_ON_ONE
            PublicLesson(
                id = lessonId.toString(),
                type = type,
                scheduledAt = row[LessonTable.scheduledAt],
                durationMinutes = row[LessonTable.durationMinutes],
                status = row[LessonTable.status],
                title = if (isGroup) row[LessonTable.title] else null,
                topic = if (isGroup) row[LessonTable.topic] else null,
                level = if (isGroup) row[LessonTable.level] else null,
                participantCount = (participantCountByLesson[lessonId] ?: 0L).toInt(),
                maxParticipants = if (isGroup) row[LessonTable.maxParticipants] else null,
            )
        }

        PublicCalendarResponse(
            teacher = PublicTeacher(
                firstName = teacherRow[UserTable.firstName],
                lastName = teacherRow[UserTable.lastName],
            ),
            lessons = lessons,
        )
    }
}

package com.gvart.parleyroom.reminder.service

import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.email.service.NotificationMailer
import com.gvart.parleyroom.email.service.SendResult
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Finds the lesson and homework reminders due at a moment and hands them to [NotificationMailer], which
 * checks preferences and sends each one at most once. Every call can therefore safely re-find the same ones.
 *
 * Lessons (CONFIRMED, teacher + confirmed students): H24 while now ∈ [start − 24h, start − [H24_LATEST]), H1
 * while now ∈ [start − 1h, start). A lesson booked or moved to less than [H24_LATEST] ahead only gets the H1 one,
 * so the "tomorrow at …" email never lands on the lesson's own day. Homework (OPEN, with a due date, students only), in the student's timezone from
 * 09:00: "due tomorrow" on the day before the due date, "overdue" on the day after.
 */
class ReminderService(private val mailer: NotificationMailer) {

    private val log = LoggerFactory.getLogger(ReminderService::class.java)

    private data class Recipient(val userId: UUID, val counterpartName: String?)

    private data class DueLesson(
        val id: UUID,
        val title: String,
        val startsAt: OffsetDateTime,
        val variant: LessonReminderVariant?,
        val recipients: List<Recipient>,
    )

    private data class OpenHomework(
        val id: UUID,
        val studentId: UUID,
        val title: String,
        val dueDate: java.time.LocalDate,
        val zone: ZoneId,
    )

    suspend fun runOnce(now: OffsetDateTime = OffsetDateTime.now()): Map<SendResult, Int> {
        val results = mutableMapOf<SendResult, Int>()
        fun count(result: SendResult) = results.merge(result, 1, Int::plus)

        for (lesson in upcomingLessons(now)) {
            val variant = lesson.variant ?: continue
            for (recipient in lesson.recipients) {
                count(mailer.sendLessonReminder(
                    recipient.userId, lesson.id, variant, lesson.title, lesson.startsAt, recipient.counterpartName,
                ))
            }
        }

        for (homework in openHomework(now)) {
            val local = now.atZoneSameInstant(homework.zone)
            if (local.toLocalTime() < SEND_FROM) continue
            val dueAt = homework.dueDate.atTime(LocalTime.of(23, 59)).atZone(homework.zone).toOffsetDateTime()
            when (local.toLocalDate()) {
                homework.dueDate.minusDays(1) ->
                    count(mailer.sendHomeworkDueTomorrow(homework.studentId, homework.id, homework.title, dueAt))
                homework.dueDate.plusDays(1) ->
                    count(mailer.sendHomeworkOverdue(homework.studentId, homework.id, homework.title, dueAt))
                else -> Unit
            }
        }

        val sent = results[SendResult.SENT] ?: 0
        val failed = results[SendResult.FAILED] ?: 0
        if (sent > 0 || failed > 0) log.info("Reminders: $sent sent, $failed failed")
        return results
    }

    private fun upcomingLessons(now: OffsetDateTime): List<DueLesson> = transaction {
        LessonTable.join(GroupTable, JoinType.LEFT, LessonTable.groupId, GroupTable.id)
            .selectAll()
            .where {
                (LessonTable.status eq LessonStatus.CONFIRMED) and
                    (LessonTable.scheduledAt greater now) and
                    (LessonTable.scheduledAt lessEq now.plusHours(24))
            }
            .map { row ->
                val id = row[LessonTable.id].value
                val startsAt = row[LessonTable.scheduledAt]
                val teacher = activeUsers(listOfNotNull(row[LessonTable.teacherId]?.value)).singleOrNull()
                val students = activeUsers(
                    LessonStudentTable.selectAll()
                        .where { (LessonStudentTable.lessonId eq id) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) }
                        .map { it[LessonStudentTable.studentId].value }
                )
                val teacherCounterpart =
                    if (row[LessonTable.type] == LessonType.ONE_ON_ONE) students.joinToString(", ") { nameOf(it) }.ifBlank { null }
                    else row.getOrNull(GroupTable.name)
                DueLesson(
                    id = id,
                    title = row[LessonTable.title],
                    startsAt = startsAt,
                    variant = when {
                        startsAt <= now.plusHours(1) -> LessonReminderVariant.H1
                        startsAt > now.plus(H24_LATEST) -> LessonReminderVariant.H24
                        else -> null
                    },
                    recipients = listOfNotNull(teacher?.let { Recipient(it[UserTable.id].value, teacherCounterpart) }) +
                        students.map { Recipient(it[UserTable.id].value, teacher?.let(::nameOf)) },
                )
            }
    }

    /** Open homework due within two days of [now] in any timezone; the caller checks the student's local date. */
    private fun openHomework(now: OffsetDateTime): List<OpenHomework> = transaction {
        val today = now.toLocalDate()
        HomeworkTable
            .join(AssignmentTable, JoinType.INNER, HomeworkTable.assignmentId, AssignmentTable.id)
            .join(UserTable, JoinType.INNER, HomeworkTable.studentId, UserTable.id)
            .selectAll()
            .where {
                (HomeworkTable.status eq HomeworkStatus.OPEN) and
                    (UserTable.status eq UserStatus.ACTIVE) and
                    AssignmentTable.dueDate.between(today.minusDays(2).toKotlinLocalDate(), today.plusDays(2).toKotlinLocalDate())
            }
            .map { row ->
                OpenHomework(
                    id = row[HomeworkTable.id].value,
                    studentId = row[HomeworkTable.studentId].value,
                    title = row[AssignmentTable.title],
                    dueDate = row[AssignmentTable.dueDate]!!.toJavaLocalDate(),
                    zone = runCatching { ZoneId.of(row[UserTable.timezone]) }.getOrDefault(ZoneId.of("Europe/Berlin")),
                )
            }
    }

    private fun activeUsers(ids: List<UUID>): List<ResultRow> =
        if (ids.isEmpty()) emptyList()
        else UserTable.selectAll().where { (UserTable.id inList ids) and (UserTable.status eq UserStatus.ACTIVE) }.toList()

    private fun nameOf(user: ResultRow) = "${user[UserTable.firstName]} ${user[UserTable.lastName]}".trim()

    companion object {
        /** The H24 reminder goes out between 24h and this long before the lesson. */
        val H24_LATEST: Duration = Duration.ofHours(20)

        /** No homework emails before this local time. */
        val SEND_FROM: LocalTime = LocalTime.of(9, 0)
    }
}

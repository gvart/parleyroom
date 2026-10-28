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
import com.gvart.parleyroom.telegram.service.TelegramNotifier
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Finds the lesson and homework reminders due at a moment and hands them to [NotificationMailer] and
 * [TelegramNotifier], which check preferences and send each one at most once per channel. Every call can therefore
 * safely re-find the same ones.
 *
 * Lessons (CONFIRMED, teacher + confirmed students): H24 while now ∈ [start − 24h, start − [H24_LATEST]), H1
 * while now ∈ [start − 1h, start). A lesson booked or moved to less than [H24_LATEST] ahead only gets the H1 one,
 * so the "tomorrow at …" email never lands on the lesson's own day. Homework (OPEN, with a due date, students only), in the student's timezone from
 * 09:00: "due tomorrow" on the day before the due date, "overdue" on the day after. Vocabulary (students with at
 * least one word due), in the student's timezone from 09:00, once per local day.
 */
class ReminderService(private val mailer: NotificationMailer, private val telegram: TelegramNotifier) {

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

    private data class VocabDue(val studentId: UUID, val dueCount: Int, val zone: ZoneId)

    suspend fun runOnce(now: OffsetDateTime = OffsetDateTime.now()) {
        val emailResults = mutableMapOf<SendResult, Int>()
        val telegramResults = mutableMapOf<SendResult, Int>()
        /** An error on one channel counts as FAILED and never keeps the other channel (or the next reminder) from sending. */
        suspend fun send(results: MutableMap<SendResult, Int>, block: suspend () -> SendResult) {
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Reminder send failed", e)
                SendResult.FAILED
            }
            results.merge(result, 1, Int::plus)
        }

        for (lesson in upcomingLessons(now)) {
            val variant = lesson.variant ?: continue
            for (recipient in lesson.recipients) {
                send(emailResults) {
                    mailer.sendLessonReminder(recipient.userId, lesson.id, variant, lesson.title, lesson.startsAt, recipient.counterpartName)
                }
                send(telegramResults) {
                    telegram.sendLessonReminder(recipient.userId, lesson.id, variant, lesson.title, lesson.startsAt, recipient.counterpartName)
                }
            }
        }

        for (homework in openHomework(now)) {
            val local = now.atZoneSameInstant(homework.zone)
            if (local.toLocalTime() < SEND_FROM) continue
            val dueAt = homework.dueDate.atTime(LocalTime.of(23, 59)).atZone(homework.zone).toOffsetDateTime()
            when (local.toLocalDate()) {
                homework.dueDate.minusDays(1) -> {
                    send(emailResults) { mailer.sendHomeworkDueTomorrow(homework.studentId, homework.id, homework.title, dueAt) }
                    send(telegramResults) { telegram.sendHomeworkDueTomorrow(homework.studentId, homework.id, homework.title) }
                }
                homework.dueDate.plusDays(1) -> {
                    send(emailResults) { mailer.sendHomeworkOverdue(homework.studentId, homework.id, homework.title, dueAt) }
                    send(telegramResults) { telegram.sendHomeworkOverdue(homework.studentId, homework.id, homework.title) }
                }
                else -> Unit
            }
        }

        for (student in vocabDue(now)) {
            val local = now.atZoneSameInstant(student.zone)
            if (local.toLocalTime() < SEND_FROM) continue
            val day = local.toLocalDate().toString()
            send(emailResults) { mailer.sendVocabDue(student.studentId, day, student.dueCount) }
            send(telegramResults) { telegram.sendVocabDue(student.studentId, day, student.dueCount) }
        }

        fun summary(results: Map<SendResult, Int>) = "${results[SendResult.SENT] ?: 0} sent, ${results[SendResult.FAILED] ?: 0} failed"
        val anything = listOf(emailResults, telegramResults).any { (it[SendResult.SENT] ?: 0) + (it[SendResult.FAILED] ?: 0) > 0 }
        if (anything) log.info("Reminders: email ${summary(emailResults)}; telegram ${summary(telegramResults)}")
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

    /** Active students with a vocabulary reminder on and at least one word due at [now]. */
    private fun vocabDue(now: OffsetDateTime): List<VocabDue> = transaction {
        val dueCount = StudentVocabTable.id.count()
        StudentVocabTable
            .join(UserTable, JoinType.INNER, StudentVocabTable.studentId, UserTable.id)
            .select(StudentVocabTable.studentId, UserTable.timezone, dueCount)
            .where {
                (StudentVocabTable.status neq StudentVocabStatus.LEARNED) and
                    (StudentVocabTable.due lessEq now) and
                    (UserTable.role eq UserRole.STUDENT) and
                    (UserTable.status eq UserStatus.ACTIVE) and
                    ((UserTable.emailVocabReminders eq true) or (UserTable.telegramVocabReminders eq true))
            }
            .groupBy(StudentVocabTable.studentId, UserTable.timezone)
            .map { row ->
                VocabDue(
                    studentId = row[StudentVocabTable.studentId].value,
                    dueCount = row[dueCount].toInt(),
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

        /** No homework or vocabulary reminders before this local time. */
        val SEND_FROM: LocalTime = LocalTime.of(9, 0)
    }
}

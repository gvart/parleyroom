package com.gvart.parleyroom.notification.service

import com.gvart.parleyroom.email.service.NotificationMailer
import com.gvart.parleyroom.email.service.SendResult
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.telegram.service.TelegramNotifier
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Email + Telegram for things that just happened (bookings, homework), next to the in-app notifications. Called inside
 * the request's transaction: the sends start on a background scope once it commits, so a rollback sends nothing and
 * the request never waits on a provider. [NotificationMailer] and [TelegramNotifier] check the recipient's toggles and
 * send each one at most once; a failure is logged and never reaches the request.
 */
class EventNotifier(private val mailer: NotificationMailer, private val telegram: TelegramNotifier) {

    private val log = LoggerFactory.getLogger(EventNotifier::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** To each of [studentIds]: the teacher scheduled the lesson, or [accepted] their request. */
    fun lessonConfirmed(lessonId: UUID, studentIds: Collection<UUID>, accepted: Boolean) = afterCommit {
        val lesson = lesson(lessonId) ?: return@afterCommit
        for (studentId in studentIds) {
            send { mailer.sendLessonConfirmed(studentId, lessonId, accepted, lesson.title, lesson.startsAt, lesson.teacherName) }
            send { telegram.sendLessonConfirmed(studentId, lessonId, accepted, lesson.title, lesson.startsAt, lesson.teacherName) }
        }
    }

    /** To the lesson's teacher: [studentId] [requested] it, or booked it confirmed. */
    fun lessonBooked(lessonId: UUID, studentId: UUID, requested: Boolean) = afterCommit {
        val lesson = lesson(lessonId) ?: return@afterCommit
        val teacherId = lesson.teacherId ?: return@afterCommit
        val studentName = transaction { nameOf(studentId) } ?: return@afterCommit
        send { mailer.sendLessonBooked(teacherId, lessonId, requested, lesson.title, lesson.startsAt, studentName) }
        send { telegram.sendLessonBooked(teacherId, lessonId, requested, lesson.title, lesson.startsAt, studentName) }
    }

    /** To the assignment's teacher, once per [attempt]. */
    fun homeworkSubmitted(homeworkId: UUID, attempt: Int) = afterCommit {
        val homework = homework(homeworkId) ?: return@afterCommit
        val teacherId = homework.teacherId ?: return@afterCommit
        val studentName = transaction { nameOf(homework.studentId) } ?: return@afterCommit
        send { mailer.sendHomeworkSubmitted(teacherId, homeworkId, attempt, homework.title, studentName) }
        send { telegram.sendHomeworkSubmitted(teacherId, homeworkId, attempt, homework.title, studentName) }
    }

    /** To the student: the teacher reviewed the homework, or [returned] it for changes. */
    fun homeworkReviewed(homeworkId: UUID, attempt: Int, returned: Boolean) = afterCommit {
        val homework = homework(homeworkId) ?: return@afterCommit
        send { mailer.sendHomeworkReviewed(homework.studentId, homeworkId, attempt, returned, homework.title) }
        send { telegram.sendHomeworkReviewed(homework.studentId, homeworkId, attempt, returned, homework.title) }
    }

    /** Waits for the sends started so far (tests). */
    suspend fun awaitIdle() = scope.coroutineContext.job.children.toList().joinAll()

    fun shutdown() = scope.cancel()

    /** Runs [block] on [scope] after the current transaction commits; right away outside of one. */
    private fun afterCommit(block: suspend () -> Unit) {
        val tx = TransactionManager.currentOrNull()
        if (tx == null) {
            launch(block)
            return
        }
        val fired = AtomicBoolean()
        tx.registerInterceptor(object : StatementInterceptor {
            override fun afterCommit(transaction: Transaction) {
                if (fired.compareAndSet(false, true)) launch(block)
            }
        })
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Event notification failed", e)
            }
        }
    }

    /** One channel's send; its failure never keeps the other channel or recipient from sending. */
    private suspend fun send(block: suspend () -> SendResult) {
        try {
            if (block() == SendResult.FAILED) log.warn("Event notification send failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Event notification send failed", e)
        }
    }

    private data class Lesson(val title: String, val startsAt: java.time.OffsetDateTime, val teacherId: UUID?, val teacherName: String?)

    private data class Homework(val title: String, val studentId: UUID, val teacherId: UUID?)

    private fun lesson(lessonId: UUID): Lesson? = transaction {
        LessonTable.selectAll().where { LessonTable.id eq lessonId }.singleOrNull()?.let { row ->
            val teacherId = row[LessonTable.teacherId]?.value
            Lesson(row[LessonTable.title], row[LessonTable.scheduledAt], teacherId, teacherId?.let(::nameOf))
        }
    }

    private fun homework(homeworkId: UUID): Homework? = transaction {
        HomeworkTable.join(AssignmentTable, JoinType.INNER, HomeworkTable.assignmentId, AssignmentTable.id)
            .selectAll()
            .where { HomeworkTable.id eq homeworkId }
            .singleOrNull()
            ?.let { Homework(it[AssignmentTable.title], it[HomeworkTable.studentId].value, it[AssignmentTable.teacherId]?.value) }
    }

    private fun nameOf(userId: UUID): String? =
        UserTable.selectAll().where { UserTable.id eq userId }.singleOrNull()
            ?.let { "${it[UserTable.firstName]} ${it[UserTable.lastName]}".trim() }
}

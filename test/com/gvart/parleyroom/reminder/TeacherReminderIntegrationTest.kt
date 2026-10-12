package com.gvart.parleyroom.reminder

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.email.data.EmailKind
import com.gvart.parleyroom.email.data.EmailLogTable
import com.gvart.parleyroom.goal.GoalFixtures
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lessonprep.LessonPrepFixtures
import com.gvart.parleyroom.lessonprep.data.LessonPlanStatus
import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.reminder.data.TeacherReminderSettingsTable
import com.gvart.parleyroom.reminder.service.TeacherReminderService
import com.gvart.parleyroom.telegram.service.TelegramSendResult
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeacherReminderIntegrationTest : IntegrationTest() {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    /** A Wednesday, well clear of DST changes. */
    private val now = OffsetDateTime.parse("2030-03-13T12:00:00Z")

    private suspend fun ApplicationTestBuilder.service(vararg flags: String): TeacherReminderService {
        startApplication()
        TeacherReminderFixtures.flags(*flags)
        return application.dependencies.resolve()
    }

    private fun types() = TeacherReminderFixtures.notifications().map { it[NotificationTable.type] }

    private fun settings(block: TeacherReminderSettingsTable.(org.jetbrains.exposed.v1.core.statements.UpsertStatement<Long>) -> Unit) = transaction {
        TeacherReminderSettingsTable.upsert {
            it[teacherId] = TEACHER
            it[updatedAt] = OffsetDateTime.now()
            block(it)
        }
    }

    // -------- After the lesson --------

    @Test
    fun `follow-up goes out hours after the end, once, naming what is missing`() = testApp {
        val service = service("teacherReminders", "lessonPrep")
        val lesson = LessonPrepFixtures.lesson(now.minusHours(3), LessonStatus.COMPLETED) // ended 2 h ago

        service.runOnce(now.minusMinutes(1))
        assertEquals(emptyList(), types())

        service.runOnce(now)
        service.runOnce(now.plusMinutes(5))
        val single = TeacherReminderFixtures.notifications().single()
        assertEquals(NotificationType.LESSON_FOLLOW_UP_DUE, single[NotificationTable.type])
        assertEquals(lesson, single[NotificationTable.referenceId])
        assertEquals(STUDENT, single[NotificationTable.actorId].value)
        assertEquals(listOf("WRAP_UP", "HOMEWORK"), single[NotificationTable.details])
        // Email is off by default and the teacher has no Telegram.
        assertTrue(sentEmails.sent.isEmpty())
        assertTrue(sentTelegrams.sent.isEmpty())
    }

    @Test
    fun `follow-up lists only what is missing and skips done lessons`() = testApp {
        val service = service("teacherReminders", "lessonPrep")
        val wrapped = LessonPrepFixtures.lesson(now.minusHours(5))
        LessonPrepFixtures.plan(wrapped, status = LessonPlanStatus.DONE)
        val complete = LessonPrepFixtures.lesson(now.minusHours(6))
        LessonPrepFixtures.plan(complete, status = LessonPlanStatus.DONE)
        LessonPrepFixtures.homework(complete, "Perfekt üben")
        // Past its end but still CONFIRMED (nobody pressed "complete").
        val forgotten = LessonPrepFixtures.lesson(now.minusHours(4), LessonStatus.CONFIRMED)
        LessonPrepFixtures.homework(forgotten, "Modalverben")
        // Too old (no backlog flood), cancelled, not ended yet.
        LessonPrepFixtures.lesson(now.minusDays(8))
        LessonPrepFixtures.lesson(now.minusHours(5), LessonStatus.CANCELLED)
        LessonPrepFixtures.lesson(now.minusMinutes(30), LessonStatus.CONFIRMED)

        service.runOnce(now)
        val details = TeacherReminderFixtures.notifications().associate { it[NotificationTable.referenceId] to it[NotificationTable.details] }
        assertEquals<Map<UUID?, List<String>?>>(mapOf(wrapped to listOf("HOMEWORK"), forgotten to listOf("WRAP_UP")), details)
    }

    @Test
    fun `with the roadmap but without lessonPrep a checked item wraps up and students without a goal only get homework`() = testApp {
        val service = service("teacherReminders", "roadmap")
        val roadmap = LessonPrepFixtures.roadmap()
        val checked = LessonPrepFixtures.lesson(now.minusHours(5))
        LessonPrepFixtures.checkItem(LessonPrepFixtures.items(roadmap.current).first(), checked)
        val open = LessonPrepFixtures.lesson(now.minusHours(4))
        val noGoal = LessonPrepFixtures.lesson(now.minusHours(4), student = com.gvart.parleyroom.ai.STUDENT_2)

        service.runOnce(now)
        val details = TeacherReminderFixtures.notifications().associate { it[NotificationTable.referenceId] to it[NotificationTable.details] }
        assertEquals<Map<UUID?, List<String>?>>(mapOf(checked to listOf("HOMEWORK"), open to listOf("WRAP_UP", "HOMEWORK"), noGoal to listOf("HOMEWORK")), details)
    }

    @Test
    fun `two pods running at once send each reminder once per channel`() = testApp {
        val service = service("teacherReminders", "lessonPrep")
        settings { it[afterLessonEmail] = true }
        TeacherReminderFixtures.setUser(TEACHER, telegramId = 777)
        LessonPrepFixtures.lesson(now.minusHours(3))
        LessonPrepFixtures.lesson(now.minusHours(4), student = com.gvart.parleyroom.ai.STUDENT_2)

        coroutineScope { List(4) { async(Dispatchers.IO) { service.runOnce(now) } }.awaitAll() }
        service.runOnce(now.plusMinutes(5))

        assertEquals(2, TeacherReminderFixtures.notifications().size)
        assertEquals(2, sentEmails.sent.size)
        assertEquals(2, sentTelegrams.sent.size)
        assertEquals(setOf("teacher@test.com"), sentEmails.sent.map { it.to }.toSet())
    }

    @Test
    fun `email and Telegram follow the per-type toggles, link the wrap-up and carry no notes`() = testApp(
        mapOf("mail.app_base_url" to "https://app.example.com"),
    ) {
        val service = service("teacherReminders", "lessonPrep")
        settings { it[afterLessonEmail] = true; it[afterLessonTelegram] = true }
        TeacherReminderFixtures.setUser(TEACHER, telegramId = 777, locale = "de")
        val lesson = LessonPrepFixtures.lesson(now.minusHours(3), notes = "GEHEIME NOTIZ")

        service.runOnce(now)
        val email = sentEmails.sent.single()
        assertEquals("Schließ die Stunde mit Test ab", email.subject)
        assertTrue("https://app.example.com/teacher/students/$STUDENT/wrap-up?lesson=$lesson" in email.html)
        assertTrue("https://app.example.com/teacher/settings" in email.html, "unsubscribe footer")
        assertFalse("GEHEIME" in email.html || "GEHEIME" in email.text)
        assertFalse("Student" in email.text, "first name only")
        val telegram = sentTelegrams.sent.single()
        assertEquals(777, telegram.chatId)
        assertEquals("https://app.example.com/teacher/students/$STUDENT/wrap-up?lesson=$lesson", telegram.button!!.url)
        assertTrue("Abhaken" in telegram.text && "Hausaufgabe erstellen" in telegram.text, telegram.text)
        assertEquals(EmailKind.TEACHER_FOLLOW_UP, transaction { EmailLogTable.selectAll().single()[EmailLogTable.kind] })
    }

    @Test
    fun `Telegram buttons open the Mini App when it is configured`() = testApp(mapOf("telegram.miniapp_base_url" to "https://mini.example.com")) {
        val service = service("teacherReminders", "lessonPrep")
        TeacherReminderFixtures.setUser(TEACHER, telegramId = 777)
        LessonPrepFixtures.lesson(now.minusHours(3))

        service.runOnce(now)
        val button = sentTelegrams.sent.single().button!!
        assertEquals("https://mini.example.com/students/$STUDENT", button.url)
        assertTrue(button.webApp)
    }

    @Test
    fun `an unreachable Telegram account or a disabled type gets nothing`() = testApp {
        val service = service("teacherReminders", "lessonPrep")
        TeacherReminderFixtures.setUser(TEACHER, telegramId = 777)
        sentTelegrams.result = TelegramSendResult.FORBIDDEN
        LessonPrepFixtures.lesson(now.minusHours(3))
        service.runOnce(now)
        assertEquals(1, sentTelegrams.sent.size)
        assertTrue(transaction { UserTable.selectAll().where { UserTable.id eq TEACHER }.single()[UserTable.telegramUnreachableAt] } != null)

        LessonPrepFixtures.lesson(now.minusHours(4), student = com.gvart.parleyroom.ai.STUDENT_2)
        service.runOnce(now)
        assertEquals(1, sentTelegrams.sent.size, "unreachable: no more attempts")
        assertEquals(2, TeacherReminderFixtures.notifications().size, "in-app still")

        settings { it[afterLessonEnabled] = false }
        LessonPrepFixtures.lesson(now.minusHours(5))
        service.runOnce(now)
        assertEquals(2, TeacherReminderFixtures.notifications().size)
    }

    @Test
    fun `nothing is sent while the flag is off`() = testApp {
        val service = service("lessonPrep", "roadmap")
        settings { it[afterLessonEmail] = true; it[digestEmail] = true }
        LessonPrepFixtures.lesson(now.minusHours(3))
        LessonPrepFixtures.lesson(now.plusHours(2), LessonStatus.CONFIRMED)
        service.runOnce(now)
        assertEquals(emptyList(), types())
        assertTrue(sentEmails.sent.isEmpty())
    }

    // -------- Before the lesson --------

    @Test
    fun `day-before reminder at 18 00 in the teacher's zone across the DST change`() = testApp {
        val service = service("teacherReminders", "lessonPrep")
        // Monday 1 April 2030, 10:00 in Berlin (CEST); Berlin switched to summer time on Sunday 31 March.
        val start = OffsetDateTime.parse("2030-04-01T08:00:00Z")
        val lesson = LessonPrepFixtures.lesson(start, LessonStatus.CONFIRMED)

        service.runOnce(OffsetDateTime.parse("2030-03-31T15:59:00Z")) // 17:59 CEST
        assertEquals(emptyList(), types())
        service.runOnce(OffsetDateTime.parse("2030-03-31T16:00:00Z")) // 18:00 CEST
        service.runOnce(OffsetDateTime.parse("2030-03-31T16:05:00Z"))
        val single = TeacherReminderFixtures.notifications().single()
        assertEquals(NotificationType.LESSON_PREP_DUE, single[NotificationTable.type])
        assertEquals(lesson, single[NotificationTable.referenceId])
        assertEquals(STUDENT, single[NotificationTable.actorId].value)

        // Moved to another day: reminded again for the new time.
        transaction {
            com.gvart.parleyroom.lesson.data.LessonTable.update({ com.gvart.parleyroom.lesson.data.LessonTable.id eq lesson }) {
                it[scheduledAt] = start.plusDays(1)
            }
        }
        service.runOnce(OffsetDateTime.parse("2030-04-01T16:00:00Z"))
        assertEquals(2, TeacherReminderFixtures.notifications().size)
    }

    @Test
    fun `hours-before mode, teacher's own zone, and a ready plan skips it`() = testApp(
        mapOf("mail.app_base_url" to "https://app.example.com"),
    ) {
        val service = service("teacherReminders", "lessonPrep")
        settings { it[beforeLessonMode] = com.gvart.parleyroom.reminder.data.BeforeLessonMode.HOURS_BEFORE; it[beforeLessonHours] = 5; it[beforeLessonEmail] = true }
        val lesson = LessonPrepFixtures.lesson(now.plusHours(5), LessonStatus.CONFIRMED)
        val prepared = LessonPrepFixtures.lesson(now.plusHours(4), LessonStatus.CONFIRMED, student = com.gvart.parleyroom.ai.STUDENT_2)
        LessonPrepFixtures.plan(prepared, student = com.gvart.parleyroom.ai.STUDENT_2, status = LessonPlanStatus.READY)
        val draft = LessonPrepFixtures.lesson(now.plusHours(3), LessonStatus.CONFIRMED, student = com.gvart.parleyroom.ai.STUDENT_2)
        LessonPrepFixtures.plan(draft, student = com.gvart.parleyroom.ai.STUDENT_2, status = LessonPlanStatus.DRAFT)

        service.runOnce(now.minusMinutes(1))
        assertEquals(listOf(draft), TeacherReminderFixtures.notifications().map { it[NotificationTable.referenceId] })
        service.runOnce(now)
        assertEquals(setOf(draft, lesson), TeacherReminderFixtures.notifications().map { it[NotificationTable.referenceId] }.toSet())
        assertTrue(sentEmails.sent.any { "https://app.example.com/teacher/students/$STUDENT/prepare?lesson=$lesson" in it.html })
    }

    @Test
    fun `no preparation reminder without lessonPrep`() = testApp {
        val service = service("teacherReminders")
        LessonPrepFixtures.lesson(now.plusHours(2), LessonStatus.CONFIRMED)
        service.runOnce(now)
        assertEquals(emptyList(), types())
    }

    // -------- Weekly digest --------

    /** The coming Monday at [time] in [zone], close to today (goal progress uses the real date). */
    private fun nextMonday(zone: ZoneId, time: LocalTime): OffsetDateTime =
        LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atTime(time).atZone(zone).toOffsetDateTime()

    @Test
    fun `digest at Monday 08 00 in the teacher's zone lists the students, once`() = testApp(
        mapOf("mail.app_base_url" to "https://app.example.com"),
    ) {
        val service = service("teacherReminders", "roadmap")
        val newYork = ZoneId.of("America/New_York")
        TeacherReminderFixtures.setUser(TEACHER, timezone = "America/New_York", telegramId = 777, locale = "en")
        settings { it[digestEmail] = true }
        LessonPrepFixtures.roadmap() // BEHIND + a PARTLY mismatch, no homework
        val at = nextMonday(newYork, LocalTime.of(8, 0))

        service.runOnce(at.minusMinutes(1))
        service.runOnce(at.plusHours(7)) // after the window
        assertEquals(emptyList(), types())

        service.runOnce(at)
        service.runOnce(at.plusMinutes(5))
        val digest = TeacherReminderFixtures.notifications().single()
        assertEquals(NotificationType.WEEKLY_DIGEST, digest[NotificationTable.type])
        assertEquals(listOf("students:1"), digest[NotificationTable.details])
        assertEquals(TEACHER, digest[NotificationTable.actorId].value)

        val email = sentEmails.sent.single()
        assertTrue("behind schedule" in email.text && "self-assessment mismatches: 1" in email.text && "no homework for 2 weeks" in email.text, email.text)
        assertTrue("https://app.example.com/teacher/dashboard" in email.html)
        assertFalse("Student" in email.text, "first name only")
        val telegram = sentTelegrams.sent.single()
        assertTrue("<b>Test</b>: behind schedule" in telegram.text, telegram.text)
    }

    @Test
    fun `digest is skipped when nobody needs attention and follows the chosen weekday`() = testApp {
        val service = service("teacherReminders")
        TeacherReminderFixtures.setUser(TEACHER, timezone = "Europe/Berlin")
        // Recent homework; roadmap and lessonPrep off, so nothing else can be due.
        LessonPrepFixtures.homework(LessonPrepFixtures.lesson(OffsetDateTime.now().minusDays(2)), "Perfekt")
        val monday = nextMonday(berlin, LocalTime.of(8, 0))
        service.runOnce(monday)
        assertEquals(emptyList(), types())

        settings { it[digestWeekday] = 2 }
        // A newly linked student without homework from this teacher needs attention.
        TeacherReminderFixtures.linkedStudent("Mia")
        service.runOnce(monday)
        assertEquals(emptyList(), types(), "Tuesday is the digest day now")
        service.runOnce(monday.plusDays(1))
        assertEquals(listOf(NotificationType.WEEKLY_DIGEST), types())
    }
}

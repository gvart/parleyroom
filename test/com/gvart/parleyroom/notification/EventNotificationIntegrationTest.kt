package com.gvart.parleyroom.notification

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.email.data.EmailLogTable
import com.gvart.parleyroom.email.service.UnsubscribeTokens
import com.gvart.parleyroom.email.transfer.EmailCategory
import com.gvart.parleyroom.email.transfer.UnsubscribeRequest
import com.gvart.parleyroom.email.transfer.UnsubscribeResponse
import com.gvart.parleyroom.homework.HomeworkFixtures
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.notification.service.EventNotifier
import com.gvart.parleyroom.reminder.service.ReminderService
import com.gvart.parleyroom.telegram.data.TelegramLogTable
import com.gvart.parleyroom.telegram.service.TelegramButton
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserResponse
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.seedStudentVocab
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventNotificationIntegrationTest : IntegrationTest() {

    private val student = UUID.fromString(STUDENT_ID)
    private val teacher = UUID.fromString(TEACHER_ID)
    private val studentChat = 200_003L
    private val teacherChat = 200_002L
    private val config = mapOf(
        "telegram.miniapp_base_url" to "https://miniapp.example.com",
        "mail.app_base_url" to "https://app.example.com",
    )

    private suspend fun ApplicationTestBuilder.events(): EventNotifier {
        startApplication()
        return application.dependencies.resolve()
    }

    /** Waits for the sends the app started after its last commit. */
    private suspend fun ApplicationTestBuilder.settle() = events().awaitIdle()

    /** After startApplication(), which reloads the test data. */
    private fun linkTelegram() = transaction {
        UserTable.update({ UserTable.id eq student }) { it[telegramId] = studentChat }
        UserTable.update({ UserTable.id eq teacher }) { it[telegramId] = teacherChat }
    }

    private fun setUser(userId: UUID, body: (org.jetbrains.exposed.v1.core.statements.UpdateStatement) -> Unit) =
        transaction { UserTable.update({ UserTable.id eq userId }) { body(it) } }

    private fun emailsTo(address: String) = sentEmails.sent.filter { it.to == address }
    private fun telegramsTo(chatId: Long) = sentTelegrams.sent.filter { it.chatId == chatId }

    private suspend fun createLesson(
        client: HttpClient,
        token: String,
        title: String = "Perfekt",
        scheduledAt: String = "2027-04-10T10:00:00+02:00",
    ): LessonResponse {
        val response = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = listOf(STUDENT_ID),
                    title = title,
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.parse(scheduledAt),
                    durationMinutes = 60,
                    topic = "Topic",
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun post(client: HttpClient, path: String, token: String, body: String? = null): HttpResponse =
        client.post(path) {
            bearerAuth(token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    // -------- Lessons --------

    @Test
    fun `a lesson the teacher creates reaches the student once on both channels`() = testApp(config) {
        startApplication()
        val client = createJsonClient(this)
        linkTelegram()
        val lesson = createLesson(client, getTeacherToken(client))
        settle()

        val email = emailsTo("student@test.com").single()
        assertEquals("Новый урок: Perfekt", email.subject)
        assertTrue("https://app.example.com/student/lessons" in email.html)
        assertTrue("Test Teacher" in email.text)
        assertTrue(email.headers.isEmpty() || "List-Unsubscribe" in email.headers)
        val message = telegramsTo(studentChat).single()
        assertTrue("Новый урок: <b>Perfekt</b>" in message.text, message.text)
        assertEquals(TelegramButton("Открыть урок", "https://miniapp.example.com/lessons", webApp = true), message.button)
        assertTrue(emailsTo("teacher@test.com").isEmpty(), "the teacher booked it, nothing to tell them")
        assertTrue(telegramsTo(teacherChat).isEmpty())

        // The same event again (e.g. a retried request) is deduplicated by the logs.
        events().lessonConfirmed(UUID.fromString(lesson.id), listOf(student), accepted = false)
        settle()
        assertEquals(1, emailsTo("student@test.com").size)
        assertEquals(1, telegramsTo(studentChat).size)
    }

    @Test
    fun `a request tells the teacher and accepting it tells the student`() = testApp(config) {
        startApplication()
        val client = createJsonClient(this)
        linkTelegram()
        val lesson = createLesson(client, getStudentToken(client))
        assertEquals(LessonStatus.REQUEST, lesson.status)
        settle()

        val request = emailsTo("teacher@test.com").single()
        assertEquals("Test Student запрашивает урок", request.subject)
        assertTrue("https://app.example.com/teacher/calendar" in request.html)
        val requestMessage = telegramsTo(teacherChat).single()
        assertEquals(TelegramButton("Посмотреть запрос", "https://app.example.com/teacher/calendar", webApp = false), requestMessage.button)
        assertTrue(emailsTo("student@test.com").isEmpty())

        assertEquals(HttpStatusCode.OK, post(client, "/api/v1/lessons/${lesson.id}/accept", getTeacherToken(client)).status)
        settle()
        assertEquals("Ваш запрос на урок принят: Perfekt", emailsTo("student@test.com").single().subject)
        assertTrue("Ваш запрос на урок принят" in telegramsTo(studentChat).single().text)
        assertEquals(1, emailsTo("teacher@test.com").size)
    }

    @Test
    fun `an auto-confirmed booking tells the teacher only`() = testApp(config) {
        startApplication()
        val client = createJsonClient(this)
        linkTelegram()
        setUser(teacher) { it[UserTable.autoConfirmBookings] = true }
        val lesson = createLesson(client, getStudentToken(client))
        assertEquals(LessonStatus.CONFIRMED, lesson.status)
        settle()

        assertEquals("Новая запись на урок: Test Student", emailsTo("teacher@test.com").single().subject)
        assertEquals("Открыть календарь", telegramsTo(teacherChat).single().button!!.text)
        assertTrue(emailsTo("student@test.com").isEmpty())
        assertTrue(telegramsTo(studentChat).isEmpty())
    }

    @Test
    fun `lesson toggles switch the booking messages off per channel`() = testApp(config) {
        startApplication()
        val client = createJsonClient(this)
        linkTelegram()
        setUser(student) { it[UserTable.emailLessonReminders] = false }
        setUser(teacher) { it[UserTable.telegramLessonReminders] = false }

        createLesson(client, getTeacherToken(client))
        createLesson(client, getStudentToken(client), title = "Request", scheduledAt = "2027-04-11T10:00:00+02:00").let { assertEquals(LessonStatus.REQUEST, it.status) }
        settle()

        assertTrue(emailsTo("student@test.com").isEmpty())
        assertEquals(1, telegramsTo(studentChat).size)
        assertEquals(1, emailsTo("teacher@test.com").size)
        assertTrue(telegramsTo(teacherChat).isEmpty())
    }

    @Test
    fun `nothing is sent when the transaction rolls back`() = testApp(config) {
        val client = createJsonClient(this)
        val lesson = UUID.fromString(createLesson(client, getTeacherToken(client)).id)
        val events = events()
        settle()
        transaction { EmailLogTable.deleteAll(); TelegramLogTable.deleteAll() }
        sentEmails.sent.clear()

        runCatching {
            transaction {
                events.lessonConfirmed(lesson, listOf(student), accepted = false)
                error("rolled back")
            }
        }
        settle()
        assertTrue(sentEmails.sent.isEmpty())

        transaction { events.lessonConfirmed(lesson, listOf(student), accepted = false) }
        settle()
        assertEquals(1, sentEmails.sent.size)
    }

    // -------- Homework --------

    @Test
    fun `each submission tells the teacher and each review tells the student`() = testApp(config) {
        startApplication()
        val client = createJsonClient(this)
        linkTelegram()
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)
        val assignment = HomeworkFixtures.assignTextTask(client, teacherToken, STUDENT_ID)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        settle()
        sentEmails.sent.clear()
        sentTelegrams.sent.clear()

        val submitted = post(client, "/api/v1/homework/$id/submit", studentToken)
        assertEquals(HttpStatusCode.OK, submitted.status, submitted.bodyAsText())
        settle()
        val submission = emailsTo("teacher@test.com").single()
        assertEquals("Задание сдано: Schreib über dein Wochenende (Test Student)", submission.subject)
        assertTrue("https://app.example.com/teacher/homework/$id" in submission.html)
        assertEquals(
            TelegramButton("Проверить задание", "https://app.example.com/teacher/homework/$id", webApp = false),
            telegramsTo(teacherChat).single().button,
        )

        val returned = post(client, "/api/v1/homework/$id/review", teacherToken, """{ "feedback": "Bitte mehr", "outcome": "RETURNED", "units": [] }""")
        assertEquals(HttpStatusCode.OK, returned.status, returned.bodyAsText())
        settle()
        assertEquals("Задание нужно доработать: Schreib über dein Wochenende", emailsTo("student@test.com").single().subject)
        val returnedMessage = telegramsTo(studentChat).single()
        assertEquals(TelegramButton("Доработать задание", "https://miniapp.example.com/homework/$id", webApp = true), returnedMessage.button)

        assertEquals(HttpStatusCode.OK, post(client, "/api/v1/homework/$id/submit", studentToken).status)
        assertEquals(HttpStatusCode.OK, post(client, "/api/v1/homework/$id/review", teacherToken, """{ "outcome": "REVIEWED", "units": [] }""").status)
        settle()
        assertEquals(2, emailsTo("teacher@test.com").size, "a resubmission is a new message")
        assertEquals(
            listOf("Задание нужно доработать: Schreib über dein Wochenende", "Ваше задание проверено: Schreib über dein Wochenende"),
            emailsTo("student@test.com").map { it.subject },
        )
        assertEquals(2, telegramsTo(studentChat).size)
        assertEquals(2, telegramsTo(teacherChat).size)

        // Closing an already reviewed homework tells the student nothing new.
        assertEquals(HttpStatusCode.OK, post(client, "/api/v1/homework/$id/review", teacherToken, """{ "outcome": "DONE", "units": [] }""").status)
        settle()
        assertEquals(2, emailsTo("student@test.com").size)
    }

    @Test
    fun `homework toggles switch the homework messages off`() = testApp(config) {
        startApplication()
        val client = createJsonClient(this)
        linkTelegram()
        setUser(teacher) {
            it[UserTable.emailHomeworkReminders] = false
            it[UserTable.telegramHomeworkReminders] = false
        }
        val assignment = HomeworkFixtures.assignTextTask(client, getTeacherToken(client), STUDENT_ID)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        assertEquals(HttpStatusCode.OK, post(client, "/api/v1/homework/$id/submit", getStudentToken(client)).status)
        settle()

        assertTrue(emailsTo("teacher@test.com").isEmpty())
        assertTrue(telegramsTo(teacherChat).isEmpty())
    }

    // -------- Vocabulary --------

    private val day = OffsetDateTime.parse("2030-03-10T00:00:00+01:00")

    /** [time] in Berlin (UTC+1 on that day). */
    private fun berlin(time: String) = OffsetDateTime.parse("2030-03-10T$time:00+01:00")

    private suspend fun ApplicationTestBuilder.reminders(): ReminderService {
        startApplication()
        return application.dependencies.resolve()
    }

    @Test
    fun `vocabulary reminder goes out once per local day from 09 00, only with words due`() = testApp(config) {
        val reminders = reminders()
        linkTelegram()
        val student2 = UUID.fromString(STUDENT_2_ID)
        setUser(student2) { it[UserTable.timezone] = "Asia/Tokyo" }
        seedStudentVocab(student, lemma = "Haus", status = StudentVocabStatus.REVIEW, due = day.minusDays(1))
        seedStudentVocab(student, lemma = "Baum", status = StudentVocabStatus.LEARNING, due = day.minusHours(1))
        seedStudentVocab(student, lemma = "Tisch", status = StudentVocabStatus.LEARNED, due = day.minusDays(1))
        seedStudentVocab(student, lemma = "Stuhl", status = StudentVocabStatus.REVIEW, due = day.plusDays(5))
        seedStudentVocab(student2, lemma = "Fenster", status = StudentVocabStatus.REVIEW, due = day.minusDays(1))

        // 08:30 in Berlin is 16:30 in Tokyo.
        reminders.runOnce(berlin("08:30"))
        assertTrue(emailsTo("student@test.com").isEmpty())
        assertEquals(1, emailsTo("student2@test.com").size)

        reminders.runOnce(berlin("09:05"))
        val email = emailsTo("student@test.com").single()
        assertEquals("Слова на повторение сегодня: 2", email.subject)
        assertTrue("https://app.example.com/student/vocabulary" in email.html)
        val message = telegramsTo(studentChat).single()
        assertEquals(TelegramButton("Начать повторение", "https://miniapp.example.com/vocab", webApp = true), message.button)

        reminders.runOnce(berlin("18:00"))
        assertEquals(1, emailsTo("student@test.com").size)
        assertEquals(1, emailsTo("student2@test.com").size)

        reminders.runOnce(berlin("09:30").plusDays(1))
        assertEquals(2, emailsTo("student@test.com").size)
        assertEquals(2, telegramsTo(studentChat).size)
        assertTrue(emailsTo("teacher@test.com").isEmpty())
    }

    @Test
    fun `vocabulary toggles and the unsubscribe link switch the reminder off`() = testApp(config) {
        val reminders = reminders()
        linkTelegram()
        seedStudentVocab(student, status = StudentVocabStatus.REVIEW, due = day.minusDays(1))
        setUser(student) { it[UserTable.telegramVocabReminders] = false }

        reminders.runOnce(berlin("10:00"))
        val email = emailsTo("student@test.com").single()
        assertTrue(telegramsTo(studentChat).isEmpty())

        val token = email.html.substringAfter("https://app.example.com/unsubscribe?token=").substringBefore('"')
        val tokens: UnsubscribeTokens = application.dependencies.resolve()
        assertEquals(student to EmailCategory.VOCAB_REMINDERS, tokens.verify(token))
        val client = createJsonClient(this)
        val response = client.post("/api/v1/email/unsubscribe") {
            contentType(ContentType.Application.Json)
            setBody(UnsubscribeRequest(token))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(EmailCategory.VOCAB_REMINDERS, response.body<UnsubscribeResponse>().category)
        transaction {
            val row = UserTable.selectAll().where { UserTable.id eq student }.single()
            assertFalse(row[UserTable.emailVocabReminders])
            assertTrue(row[UserTable.emailLessonReminders])
        }

        reminders.runOnce(berlin("10:00").plusDays(1))
        assertEquals(1, emailsTo("student@test.com").size)
        assertTrue(telegramsTo(studentChat).isEmpty())
    }

    @Test
    fun `vocabulary toggles are read and patched on users me`() = testApp(config) {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val before = client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>()
        assertTrue(before.emailVocabReminders)
        assertTrue(before.telegramVocabReminders)

        val patched = client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateProfileRequest(emailVocabReminders = false))
        }
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertFalse(patched.body<UserResponse>().emailVocabReminders)
        assertTrue(patched.body<UserResponse>().telegramVocabReminders)

        client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody("""{ "telegramVocabReminders": false }""")
        }
        val after = client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>()
        assertFalse(after.emailVocabReminders)
        assertFalse(after.telegramVocabReminders)
    }
}

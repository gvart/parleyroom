package com.gvart.parleyroom.telegram

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.email.service.SendResult
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.telegram.data.TelegramLogTable
import com.gvart.parleyroom.telegram.service.TelegramButton
import com.gvart.parleyroom.telegram.service.TelegramNotifier
import com.gvart.parleyroom.telegram.service.TelegramSendResult
import com.gvart.parleyroom.user.TelegramInitData
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.TelegramAuthRequest
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramNotificationIntegrationTest : IntegrationTest() {

    private val student = UUID.fromString(STUDENT_ID)
    private val teacher = UUID.fromString(TEACHER_ID)
    private val studentChat = 200_003L
    private val miniAppConfig = mapOf(
        "telegram.miniapp_base_url" to "https://miniapp.example.com/",
        "mail.app_base_url" to "https://app.example.com",
    )

    private suspend fun ApplicationTestBuilder.notifier(): TelegramNotifier {
        startApplication()
        return application.dependencies.resolve()
    }

    private fun link(userId: UUID, chatId: Long) =
        transaction { UserTable.update({ UserTable.id eq userId }) { it[telegramId] = chatId } }

    private fun unreachableAt(userId: UUID) =
        transaction { UserTable.selectAll().where { UserTable.id eq userId }.single()[UserTable.telegramUnreachableAt] }

    private fun markUnreachable(userId: UUID) =
        transaction { UserTable.update({ UserTable.id eq userId }) { it[telegramUnreachableAt] = OffsetDateTime.now() } }

    // -------- Reminders --------

    @Test
    fun `reminders go to linked users only, with a web_app button into the Mini App`() = testApp(miniAppConfig) {
        val notifier = notifier()
        link(student, studentChat)
        val at = OffsetDateTime.now().plusDays(1)
        val homework = UUID.randomUUID()

        assertEquals(SendResult.SENT, notifier.sendLessonReminder(student, UUID.randomUUID(), LessonReminderVariant.H24, "Perfekt", at, "Anna"))
        assertEquals(SendResult.SKIPPED, notifier.sendLessonReminder(teacher, UUID.randomUUID(), LessonReminderVariant.H24, "Perfekt", at, null))
        assertEquals(SendResult.SENT, notifier.sendHomeworkOverdue(student, homework, "Modalverben"))

        val (lesson, overdue) = sentTelegrams.sent
        assertEquals(studentChat, lesson.chatId)
        assertEquals(TelegramButton("Открыть урок", "https://miniapp.example.com/lessons", webApp = true), lesson.button)
        assertTrue("<b>Perfekt</b>" in lesson.text, lesson.text)
        assertTrue("Преподаватель: Anna" in lesson.text, lesson.text)
        assertEquals("https://miniapp.example.com/homework/$homework", overdue.button!!.url)
        assertEquals(2, sentTelegrams.sent.size)
    }

    @Test
    fun `teachers get the calendar and every role falls back to portal links without a Mini App`() = testApp(mapOf("mail.app_base_url" to "https://app.example.com")) {
        val notifier = notifier()
        link(student, studentChat)
        link(teacher, 200_002L)
        val homework = UUID.randomUUID()

        notifier.sendLessonReminder(teacher, UUID.randomUUID(), LessonReminderVariant.H1, "L", OffsetDateTime.now().plusHours(1), "Test Student")
        notifier.sendHomeworkDueTomorrow(student, homework, "HW")

        val (toTeacher, toStudent) = sentTelegrams.sent
        assertEquals(TelegramButton("Открыть урок", "https://app.example.com/teacher/calendar", webApp = false), toTeacher.button)
        assertTrue("Участники: Test Student" in toTeacher.text, toTeacher.text)
        assertEquals(TelegramButton("Открыть задание", "https://app.example.com/student/homework/$homework", webApp = false), toStudent.button)
    }

    @Test
    fun `category toggles are respected`() = testApp {
        val notifier = notifier()
        link(student, studentChat)
        transaction { UserTable.update({ UserTable.id eq student }) { it[telegramHomeworkReminders] = false } }

        assertEquals(SendResult.OPTED_OUT, notifier.sendHomeworkDueTomorrow(student, UUID.randomUUID(), "HW"))
        assertEquals(SendResult.SENT, notifier.sendLessonReminder(student, UUID.randomUUID(), LessonReminderVariant.H1, "L", OffsetDateTime.now(), null))

        transaction { UserTable.update({ UserTable.id eq student }) { it[telegramLessonReminders] = false } }
        assertEquals(SendResult.OPTED_OUT, notifier.sendLessonReminder(student, UUID.randomUUID(), LessonReminderVariant.H1, "L", OffsetDateTime.now(), null))
        assertEquals(1, sentTelegrams.sent.size)
    }

    @Test
    fun `a reminder goes out once, and a failed one is retried once old enough`() = testApp {
        val notifier = notifier()
        link(student, studentChat)
        val lesson = UUID.randomUUID()
        val at = OffsetDateTime.now().plusDays(1)
        assertEquals(SendResult.SENT, notifier.sendLessonReminder(student, lesson, LessonReminderVariant.H24, "L", at, null))
        assertEquals(SendResult.ALREADY_SENT, notifier.sendLessonReminder(student, lesson, LessonReminderVariant.H24, "L", at, null))
        assertEquals(SendResult.SENT, notifier.sendLessonReminder(student, lesson, LessonReminderVariant.H1, "L", at, null))

        val homework = UUID.randomUUID()
        sentTelegrams.result = TelegramSendResult.FAILED
        assertEquals(SendResult.FAILED, notifier.sendHomeworkDueTomorrow(student, homework, "HW"))
        sentTelegrams.result = TelegramSendResult.SENT
        assertEquals(SendResult.ALREADY_SENT, notifier.sendHomeworkDueTomorrow(student, homework, "HW"))
        transaction {
            TelegramLogTable.update({ TelegramLogTable.refId eq homework }) {
                it[sentAt] = OffsetDateTime.now().minus(TelegramNotifier.RETRY_FAILED_AFTER).minusSeconds(1)
            }
        }
        assertEquals(SendResult.SENT, notifier.sendHomeworkDueTomorrow(student, homework, "HW"))
        assertEquals(SendResult.ALREADY_SENT, notifier.sendHomeworkDueTomorrow(student, homework, "HW"))
        assertNull(unreachableAt(student))

        val row = transaction { TelegramLogTable.selectAll().where { TelegramLogTable.refId eq homework }.single() }
        assertTrue(row[TelegramLogTable.success])
        assertEquals(studentChat, row[TelegramLogTable.chatId])
        assertEquals(4, sentTelegrams.sent.size)
    }

    @Test
    fun `a 403 marks the user unreachable and stops further sends until relinked`() = testApp {
        val notifier = notifier()
        link(student, studentChat)
        sentTelegrams.result = TelegramSendResult.FORBIDDEN
        assertEquals(SendResult.FAILED, notifier.sendHomeworkOverdue(student, UUID.randomUUID(), "HW"))
        assertNotNull(unreachableAt(student))

        sentTelegrams.result = TelegramSendResult.SENT
        assertEquals(SendResult.SKIPPED, notifier.sendHomeworkOverdue(student, UUID.randomUUID(), "HW"))
        assertEquals(1, sentTelegrams.sent.size)

        val client = createJsonClient(this)
        val token = getStudentToken(client)
        assertFalse(client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>().telegramReachable)

        val relink = client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(TelegramAuthRequest(TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = studentChat)))
        }
        assertEquals(HttpStatusCode.OK, relink.status)
        assertNull(unreachableAt(student))
        assertTrue(client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>().telegramReachable)
        assertEquals(SendResult.SENT, notifier.sendHomeworkOverdue(student, UUID.randomUUID(), "HW"))
    }

    @Test
    fun `unlinking clears the unreachable flag`() = testApp {
        startApplication()
        link(student, studentChat)
        markUnreachable(student)
        val client = createJsonClient(this)
        val response = client.delete("/api/v1/users/me/telegram") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(unreachableAt(student))
    }

    // -------- Test endpoint --------

    @Test
    fun `test message succeeds with 204 and clears the unreachable flag`() = testApp {
        startApplication()
        link(student, studentChat)
        markUnreachable(student)
        val client = createJsonClient(this)
        val response = client.post("/api/v1/users/me/telegram/test") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertNull(unreachableAt(student))
        val message = sentTelegrams.sent.single()
        assertEquals(studentChat, message.chatId)
        assertTrue("работают" in message.text, message.text)
    }

    @Test
    fun `test message without a linked account is 400 TELEGRAM_NOT_LINKED`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/users/me/telegram/test") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("TELEGRAM_NOT_LINKED", response.body<ProblemDetail>().code)
        assertTrue(sentTelegrams.sent.isEmpty())
    }

    @Test
    fun `test message refused by Telegram is 409 TELEGRAM_UNREACHABLE and marks the user`() = testApp {
        startApplication()
        link(student, studentChat)
        sentTelegrams.result = TelegramSendResult.FORBIDDEN
        val client = createJsonClient(this)
        val response = client.post("/api/v1/users/me/telegram/test") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("TELEGRAM_UNREACHABLE", response.body<ProblemDetail>().code)
        assertNotNull(unreachableAt(student))
    }

    @Test
    fun `test message failing otherwise is 502`() = testApp {
        startApplication()
        link(student, studentChat)
        sentTelegrams.result = TelegramSendResult.FAILED
        val client = createJsonClient(this)
        val response = client.post("/api/v1/users/me/telegram/test") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertEquals("TELEGRAM_SEND_FAILED", response.body<ProblemDetail>().code)
        assertNull(unreachableAt(student))
    }

    // -------- Profile --------

    @Test
    fun `profile exposes and updates the Telegram toggles`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val before = client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>()
        assertTrue(before.telegramLessonReminders)
        assertTrue(before.telegramHomeworkReminders)
        assertFalse(before.telegramReachable)

        val response = client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateProfileRequest(telegramLessonReminders = false))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val after = response.body<UserResponse>()
        assertFalse(after.telegramLessonReminders)
        assertTrue(after.telegramHomeworkReminders)

        link(teacher, 200_002L)
        assertTrue(client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>().telegramReachable)
    }
}

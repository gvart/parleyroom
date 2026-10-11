package com.gvart.parleyroom.email

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.email.data.EmailKind
import com.gvart.parleyroom.email.data.EmailLogTable
import com.gvart.parleyroom.email.service.NotificationMailer
import com.gvart.parleyroom.email.service.SendResult
import com.gvart.parleyroom.email.service.UnsubscribeTokens
import com.gvart.parleyroom.email.template.EmailLanguage
import com.gvart.parleyroom.email.template.LessonReminderVariant
import com.gvart.parleyroom.email.transfer.EmailCategory
import com.gvart.parleyroom.email.transfer.UnsubscribeRequest
import com.gvart.parleyroom.email.transfer.UnsubscribeResponse
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
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
import kotlin.test.assertTrue

class EmailNotificationIntegrationTest : IntegrationTest() {

    private val student = UUID.fromString(STUDENT_ID)
    private val httpsConfig = mapOf(
        "mail.api_public_url" to "https://api.example.com",
        "mail.app_base_url" to "https://app.example.com",
        "mail.brand_name" to "Sprach|robbe",
    )

    private suspend fun ApplicationTestBuilder.mailer(): NotificationMailer {
        startApplication()
        return application.dependencies.resolve()
    }

    private suspend fun ApplicationTestBuilder.tokens(): UnsubscribeTokens {
        startApplication()
        return application.dependencies.resolve()
    }

    private fun prefs(userId: UUID) = transaction {
        UserTable.selectAll().where { UserTable.id eq userId }.single()
            .let { it[UserTable.emailLessonReminders] to it[UserTable.emailHomeworkReminders] }
    }

    // -------- Reminders --------

    @Test
    fun `lesson reminder carries unsubscribe link and one-click headers`() = testApp(httpsConfig) {
        val result = mailer().sendLessonReminder(
            student, UUID.randomUUID(), LessonReminderVariant.H24, "Perfekt", OffsetDateTime.now().plusDays(1), "Anna Teacher",
        )
        assertEquals(SendResult.SENT, result)
        val email = sentEmails.sent.single()
        assertEquals("student@test.com", email.to)
        val header = email.headers.getValue("List-Unsubscribe")
        assertTrue(header.startsWith("<https://api.example.com/api/v1/email/unsubscribe?token="), header)
        assertEquals("List-Unsubscribe=One-Click", email.headers["List-Unsubscribe-Post"])
        assertTrue("https://app.example.com/unsubscribe?token=" in email.html)
        assertTrue("https://app.example.com/student/settings" in email.html)
        assertTrue("Sprachrobbe" in email.text)
        assertEquals("robbi", email.inlineImages.single().contentId)
    }

    @Test
    fun `a reminder goes out once per ref and variant`() = testApp {
        val mailer = mailer()
        val lesson = UUID.randomUUID()
        val at = OffsetDateTime.now().plusDays(1)
        assertEquals(SendResult.SENT, mailer.sendLessonReminder(student, lesson, LessonReminderVariant.H24, "L", at, null))
        assertEquals(SendResult.ALREADY_SENT, mailer.sendLessonReminder(student, lesson, LessonReminderVariant.H24, "L", at, null))
        assertEquals(SendResult.SENT, mailer.sendLessonReminder(student, lesson, LessonReminderVariant.H1, "L", at, null))
        assertEquals(2, sentEmails.sent.size)

        val homework = UUID.randomUUID()
        assertEquals(SendResult.SENT, mailer.sendHomeworkDueTomorrow(student, homework, "HW", at))
        assertEquals(SendResult.ALREADY_SENT, mailer.sendHomeworkDueTomorrow(student, homework, "HW", at))
        assertEquals(SendResult.SENT, mailer.sendHomeworkOverdue(student, homework, "HW", at))

        val logged = transaction { EmailLogTable.selectAll().where { EmailLogTable.recipientUserId eq student }.count() }
        assertEquals(4, logged)
    }

    @Test
    fun `opted out users get no reminder and plain http skips the headers`() = testApp {
        val mailer = mailer()
        transaction { UserTable.update({ UserTable.id eq student }) { it[emailHomeworkReminders] = false } }
        assertEquals(SendResult.OPTED_OUT, mailer.sendHomeworkOverdue(student, UUID.randomUUID(), "HW", OffsetDateTime.now()))
        assertTrue(sentEmails.sent.isEmpty())

        assertEquals(SendResult.SENT, mailer.sendLessonReminder(student, UUID.randomUUID(), LessonReminderVariant.H1, "L", OffsetDateTime.now(), null))
        assertTrue(sentEmails.sent.single().headers.isEmpty())
    }

    @Test
    fun `failed send is logged and reported`() = testApp {
        val mailer = mailer()
        sentEmails.succeed = false
        val homework = UUID.randomUUID()
        assertEquals(SendResult.FAILED, mailer.sendHomeworkDueTomorrow(student, homework, "HW", OffsetDateTime.now()))
        val row = transaction { EmailLogTable.selectAll().where { EmailLogTable.refId eq homework }.single() }
        assertFalse(row[EmailLogTable.success])
    }

    @Test
    fun `a failed reminder is retried once the attempt is old enough`() = testApp {
        val mailer = mailer()
        val homework = UUID.randomUUID()
        sentEmails.succeed = false
        assertEquals(SendResult.FAILED, mailer.sendHomeworkDueTomorrow(student, homework, "HW", OffsetDateTime.now()))
        sentEmails.succeed = true
        // Too fresh: the claim might still be in flight.
        assertEquals(SendResult.ALREADY_SENT, mailer.sendHomeworkDueTomorrow(student, homework, "HW", OffsetDateTime.now()))

        transaction {
            EmailLogTable.update({ EmailLogTable.refId eq homework }) {
                it[sentAt] = OffsetDateTime.now().minus(NotificationMailer.RETRY_FAILED_AFTER).minusSeconds(1)
            }
        }
        assertEquals(SendResult.SENT, mailer.sendHomeworkDueTomorrow(student, homework, "HW", OffsetDateTime.now()))
        assertEquals(SendResult.ALREADY_SENT, mailer.sendHomeworkDueTomorrow(student, homework, "HW", OffsetDateTime.now()))
        assertEquals(2, sentEmails.sent.size)
        val row = transaction { EmailLogTable.selectAll().where { EmailLogTable.refId eq homework }.single() }
        assertTrue(row[EmailLogTable.success])
    }

    @Test
    fun `a lesson reminder is deduplicated per start time`() = testApp {
        val mailer = mailer()
        val lesson = UUID.randomUUID()
        val at = OffsetDateTime.now().plusDays(1)
        assertEquals(SendResult.SENT, mailer.sendLessonReminder(student, lesson, LessonReminderVariant.H24, "L", at, null))
        assertEquals(SendResult.SENT, mailer.sendLessonReminder(student, lesson, LessonReminderVariant.H24, "L", at.plusHours(2), null))
        assertEquals(SendResult.ALREADY_SENT, mailer.sendLessonReminder(student, lesson, LessonReminderVariant.H24, "L", at.plusHours(2), null))
    }

    @Test
    fun `invite email links to registration and is logged`() = testApp(httpsConfig) {
        val registration = UUID.randomUUID()
        val sent = mailer().sendInvite(
            "new+1@test.com", EmailLanguage.UK, "Anna", UserRole.STUDENT, "tok en", OffsetDateTime.now().plusDays(7), registration,
        )
        assertTrue(sent)
        val email = sentEmails.sent.single()
        assertTrue("https://app.example.com/register?token=tok+en&amp;email=new%2B1%40test.com" in email.html, email.html)
        assertTrue(email.headers.isEmpty())
        assertTrue("Anna" in email.subject)
        val row = transaction { EmailLogTable.selectAll().where { EmailLogTable.refId eq registration }.single() }
        assertEquals(EmailKind.INVITE, row[EmailLogTable.kind])
        assertTrue(row[EmailLogTable.success])
    }

    // -------- Unsubscribe endpoint --------

    @Test
    fun `portal unsubscribe with a JSON token is idempotent`() = testApp {
        val token = tokens().token(student, EmailCategory.LESSON_REMINDERS)
        val client = createJsonClient(this)
        repeat(2) {
            val response = client.post("/api/v1/email/unsubscribe") {
                contentType(ContentType.Application.Json)
                setBody(UnsubscribeRequest(token))
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(EmailCategory.LESSON_REMINDERS, response.body<UnsubscribeResponse>().category)
        }
        assertEquals(false to true, prefs(student))
    }

    @Test
    fun `one-click unsubscribe takes the token from the query`() = testApp {
        val token = tokens().token(student, EmailCategory.HOMEWORK_REMINDERS)
        val client = createJsonClient(this)
        val response = client.submitForm(
            url = "/api/v1/email/unsubscribe?token=$token",
            formParameters = parameters { append("List-Unsubscribe", "One-Click") },
        )
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(EmailCategory.HOMEWORK_REMINDERS, response.body<UnsubscribeResponse>().category)
        assertEquals(true to false, prefs(student))
    }

    @Test
    fun `bad tokens are rejected`() = testApp {
        val valid = tokens().token(student, EmailCategory.LESSON_REMINDERS)
        val tampered = valid.dropLast(1) + if (valid.last() == 'A') 'B' else 'A'
        val client = createJsonClient(this)
        for (token in listOf("garbage", "", tampered)) {
            val response = client.post("/api/v1/email/unsubscribe") {
                contentType(ContentType.Application.Json)
                setBody(UnsubscribeRequest(token))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("UNSUBSCRIBE_TOKEN_INVALID", response.body<ProblemDetail>().code)
        }
        val noToken = client.submitForm(url = "/api/v1/email/unsubscribe", formParameters = parameters { append("List-Unsubscribe", "One-Click") })
        assertEquals(HttpStatusCode.BadRequest, noToken.status)
        assertEquals(true to true, prefs(student))
    }
}

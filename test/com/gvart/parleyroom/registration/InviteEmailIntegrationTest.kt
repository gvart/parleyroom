package com.gvart.parleyroom.registration

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.admin.data.AppSettingsTable
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.registration.service.RegistrationService
import com.gvart.parleyroom.registration.transfer.InviteListResponse
import com.gvart.parleyroom.registration.transfer.InviteStatus
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class InviteEmailIntegrationTest : IntegrationTest() {

    @Test
    fun `student invite emails the invitee in their native language with the configured expiry`() = testApp {
        startApplication() // loads the test data, which empties app_settings, before seeding
        setInviteExpiryDays(12)
        val client = createJsonClient(this)
        val email = "${Uuid.random()}@test.com"

        val response = invite(client, getTeacherToken(client), email, "STUDENT", nativeLanguage = "uk")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<InviteUserResponse>()
        assertTrue(body.emailSent)
        assertCloseTo(OffsetDateTime.now().plusDays(12), body.expiresAt)

        val sent = sentEmails.sent.single()
        assertEquals(email, sent.to)
        assertTrue(sent.subject.startsWith("Test Teacher запрошує вас"), sent.subject)
        assertEquals(body.token, tokenFrom(sent.text))

        val row = registration(email)
        assertNotNull(row[RegistrationTable.lastSentAt])
        assertCloseTo(body.expiresAt, row[RegistrationTable.expiresAt])
    }

    @Test
    fun `teacher invite by an admin is written in the admin's UI locale`() = testApp {
        startApplication()
        transaction { UserTable.update({ UserTable.id eq UUID.fromString(ADMIN_ID) }) { it[locale] = "de" } }
        val client = createJsonClient(this)
        val email = "${Uuid.random()}@test.com"

        val response = invite(client, getAdminToken(client), email, "TEACHER")

        assertEquals(HttpStatusCode.Created, response.status)
        val sent = sentEmails.sent.single()
        assertEquals(email, sent.to)
        assertTrue(sent.subject.startsWith("Test Admin hat dich"), sent.subject)
    }

    @Test
    fun `failed send keeps the invite and reports emailSent false`() = testApp {
        sentEmails.succeed = false
        val client = createJsonClient(this)
        val email = "${Uuid.random()}@test.com"

        val response = invite(client, getAdminToken(client), email, "STUDENT")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<InviteUserResponse>()
        assertEquals(false, body.emailSent)
        assertEquals(1, sentEmails.sent.size)
        assertEquals(RegistrationService.hashToken(body.token), registration(email)[RegistrationTable.token])
        assertNull(registration(email)[RegistrationTable.lastSentAt])
    }

    @Test
    fun `invitee signs up through the emailed link`() = testApp {
        val client = createJsonClient(this)
        val email = "${Uuid.random()}@test.com"
        invite(client, getTeacherToken(client), email, "STUDENT")

        val response = register(client, tokenFrom(sentEmails.sent.single().text), email)

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(InviteStatus.USED, listInvites(client, getAdminToken(client)).single { it.email == email }.status)
    }

    @Test
    fun `admins list every invite and teachers only their own`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val teacherToken = getTeacherToken(client)
        val byAdmin = "${Uuid.random()}@test.com"
        val pending = "${Uuid.random()}@test.com"
        val expired = "${Uuid.random()}@test.com"
        val used = "${Uuid.random()}@test.com"
        invite(client, adminToken, byAdmin, "TEACHER")
        invite(client, teacherToken, pending, "STUDENT")
        invite(client, teacherToken, expired, "STUDENT")
        invite(client, teacherToken, used, "STUDENT")
        expire(expired)
        register(client, tokenFrom(sentEmails.sent.last().text), used)

        // The test data seeds three more invites (newuser@ by the teacher, expired@ and used@ by nobody).
        val all = listInvites(client, adminToken)
        assertEquals(listOf(used, expired, pending, byAdmin), all.take(4).map { it.email })
        assertEquals(7, all.size)
        assertNull(all.single { it.email == "expired@test.com" }.invitedBy)
        val statuses = all.associate { it.email to it.status }
        assertEquals(InviteStatus.PENDING, statuses[byAdmin])
        assertEquals(InviteStatus.PENDING, statuses[pending])
        assertEquals(InviteStatus.EXPIRED, statuses[expired])
        assertEquals(InviteStatus.USED, statuses[used])
        val adminInvite = all.single { it.email == byAdmin }
        assertEquals(UserRole.TEACHER, adminInvite.role)
        assertEquals(ADMIN_ID, adminInvite.invitedBy?.id)
        assertEquals("Test Admin", adminInvite.invitedBy?.name)
        assertNotNull(adminInvite.lastSentAt)

        assertEquals(listOf(used, expired, pending, "newuser@test.com"), listInvites(client, teacherToken).map { it.email })
        assertEquals(listOf(expired), listInvites(client, teacherToken, "EXPIRED").map { it.email })
        assertEquals(listOf(pending, "newuser@test.com"), listInvites(client, teacherToken, "PENDING").map { it.email })
        assertEquals(listOf(used, "used@test.com"), listInvites(client, adminToken, "USED").map { it.email })

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/registration/invites") { bearerAuth(getStudentToken(client)) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/registration/invites?status=NOPE") { bearerAuth(adminToken) }.status)
    }

    @Test
    fun `resend rotates the token, restarts the expiry and emails the new link`() = testApp {
        startApplication()
        setInviteExpiryDays(5)
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val first = invite(client, teacherToken, email, "STUDENT").body<InviteUserResponse>()
        expire(email)
        val id = registration(email)[RegistrationTable.id].value
        assertEquals(InviteStatus.EXPIRED, listInvites(client, teacherToken).single { it.id == id.toString() }.status)

        val response = client.post("/api/v1/registration/invites/$id/resend") { bearerAuth(teacherToken) }

        assertEquals(HttpStatusCode.OK, response.status)
        val resent = response.body<InviteUserResponse>()
        assertTrue(resent.emailSent)
        assertNotEquals(first.token, resent.token)
        assertCloseTo(OffsetDateTime.now().plusDays(5), resent.expiresAt)
        assertEquals(2, sentEmails.sent.size)
        assertEquals(email, sentEmails.sent.last().to)
        assertEquals(resent.token, tokenFrom(sentEmails.sent.last().text))
        assertEquals(InviteStatus.PENDING, listInvites(client, teacherToken).single { it.id == id.toString() }.status)

        val oldLink = register(client, first.token, email)
        assertEquals(HttpStatusCode.NotFound, oldLink.status)
        assertEquals("REGISTRATION_LINK_INVALID", oldLink.body<ProblemDetail>().code)
        assertEquals(HttpStatusCode.Created, register(client, resent.token, email).status)
    }

    @Test
    fun `resend reports a failed send without failing`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val email = "${Uuid.random()}@test.com"
        invite(client, adminToken, email, "STUDENT")
        val firstSentAt = registration(email)[RegistrationTable.lastSentAt]
        sentEmails.succeed = false

        val response = client.post("/api/v1/registration/invites/${registration(email)[RegistrationTable.id].value}/resend") { bearerAuth(adminToken) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(false, response.body<InviteUserResponse>().emailSent)
        assertEquals(firstSentAt?.toInstant(), registration(email)[RegistrationTable.lastSentAt]?.toInstant())
    }

    @Test
    fun `resend errors`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val teacherToken = getTeacherToken(client)
        val adminsInvite = "${Uuid.random()}@test.com"
        val usedInvite = "${Uuid.random()}@test.com"
        invite(client, adminToken, adminsInvite, "TEACHER")
        invite(client, teacherToken, usedInvite, "STUDENT")
        register(client, tokenFrom(sentEmails.sent.last().text), usedInvite)
        val existingUserInvite = transaction {
            RegistrationTable.insertAndGetId {
                it[email] = "student2@test.com"
                it[token] = RegistrationService.hashToken("seeded")
                it[role] = UserRole.STUDENT
                it[expiresAt] = OffsetDateTime.now().plusDays(1)
                it[invitedBy] = UUID.fromString(ADMIN_ID)
            }.value
        }
        suspend fun resend(id: Any, token: String) = client.post("/api/v1/registration/invites/$id/resend") { bearerAuth(token) }

        val notFound = resend(UUID.randomUUID(), adminToken)
        assertEquals(HttpStatusCode.NotFound, notFound.status)
        assertEquals("INVITATION_NOT_FOUND", notFound.body<ProblemDetail>().code)

        val adminsId = registration(adminsInvite)[RegistrationTable.id].value
        assertEquals(HttpStatusCode.Forbidden, resend(adminsId, teacherToken).status)
        assertEquals(HttpStatusCode.Forbidden, resend(adminsId, getStudentToken(client)).status)

        val used = resend(registration(usedInvite)[RegistrationTable.id].value, teacherToken)
        assertEquals(HttpStatusCode.BadRequest, used.status)
        assertEquals("INVITATION_ALREADY_USED", used.body<ProblemDetail>().code)

        val exists = resend(existingUserInvite, adminToken)
        assertEquals(HttpStatusCode.Conflict, exists.status)
        assertEquals("EMAIL_ALREADY_EXISTS", exists.body<ProblemDetail>().code)

        assertEquals(HttpStatusCode.OK, resend(adminsId, adminToken).status)
    }

    private suspend fun invite(client: HttpClient, token: String, email: String, role: String, nativeLanguage: String? = null): HttpResponse =
        client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(buildMap {
                put("email", email)
                put("role", role)
                nativeLanguage?.let { put("nativeLanguage", it) }
            })
        }

    private suspend fun register(client: HttpClient, token: String, email: String): HttpResponse =
        client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("token" to token, "firstName" to "New", "lastName" to "User", "email" to email, "password" to "password123"))
        }

    private suspend fun listInvites(client: HttpClient, token: String, status: String? = null) =
        client.get("/api/v1/registration/invites" + (status?.let { "?status=$it" } ?: "")) { bearerAuth(token) }
            .also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<InviteListResponse>().invites

    private fun tokenFrom(text: String): String =
        Regex("""/register\?token=([^&\s]+)""").find(text)!!.groupValues[1]

    private fun registration(email: String) = transaction {
        RegistrationTable.selectAll().where { RegistrationTable.email eq email }.single()
    }

    private fun expire(email: String) = transaction {
        RegistrationTable.update({ RegistrationTable.email eq email }) { it[expiresAt] = OffsetDateTime.now().minusDays(1) }
    }

    private fun setInviteExpiryDays(days: Int) {
        transaction {
            AppSettingsTable.upsert {
                it[id] = AppSettingsTable.ROW_ID
                it[inviteExpiryDays] = days
                it[updatedAt] = OffsetDateTime.now()
            }
        }
    }

    private fun assertCloseTo(expected: OffsetDateTime, actual: OffsetDateTime) =
        assertTrue(Duration.between(expected, actual).abs() < Duration.ofMinutes(1), "expected ~$expected, was $actual")
}

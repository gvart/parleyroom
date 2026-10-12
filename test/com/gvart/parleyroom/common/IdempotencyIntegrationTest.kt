package com.gvart.parleyroom.common

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.routing.IDEMPOTENCY_KEY_HEADER
import com.gvart.parleyroom.common.routing.IDEMPOTENT_REPLAYED_HEADER
import com.gvart.parleyroom.common.service.IdempotencyService
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.user.data.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdempotencyIntegrationTest : IntegrationTest() {

    private fun lesson(scheduledAt: String = "2027-04-10T10:00:00+02:00", title: String = "German Lesson") =
        CreateLessonRequest(
            teacherId = TEACHER_ID,
            studentIds = listOf(STUDENT_ID),
            title = title,
            type = LessonType.ONE_ON_ONE,
            scheduledAt = OffsetDateTime.parse(scheduledAt),
            durationMinutes = 60,
            topic = "Conversation practice",
        )

    private suspend fun createLesson(client: HttpClient, token: String, body: CreateLessonRequest, key: String?): HttpResponse =
        client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            key?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
            setBody(body)
        }

    private fun count(sql: String): Long = transaction {
        exec(sql) { rs -> rs.next(); rs.getLong(1) }!!
    }

    private fun lessonCount() = count("SELECT count(*) FROM lessons")

    private suspend fun HttpResponse.assertProblem(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status)
        assertEquals(code, body<ProblemDetail>().code)
    }

    @Test
    fun `same key and payload replays the stored response`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val first = createLesson(client, token, lesson(), "key-1")
        val second = createLesson(client, token, lesson(), "key-1")

        assertEquals(HttpStatusCode.Created, first.status)
        assertNull(first.headers[IDEMPOTENT_REPLAYED_HEADER])
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals("true", second.headers[IDEMPOTENT_REPLAYED_HEADER])
        assertEquals(first.body<LessonResponse>().id, second.body<LessonResponse>().id)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, lessonCount())
    }

    @Test
    fun `without the header a duplicate is created as before`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        assertEquals(HttpStatusCode.Created, createLesson(client, token, lesson(), null).status)
        // The identical second lesson overlaps the first: the endpoint's own rule, not a replay.
        createLesson(client, token, lesson(), null).assertProblem(HttpStatusCode.Conflict, "AVAILABILITY_OVERLAP")
        assertEquals(0, count("SELECT count(*) FROM idempotency_keys"))
    }

    @Test
    fun `same key with another payload or route is IDEMPOTENCY_KEY_REUSED`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        assertEquals(HttpStatusCode.Created, createLesson(client, token, lesson(), "key-1").status)

        createLesson(client, token, lesson(title = "Other"), "key-1")
            .assertProblem(HttpStatusCode.UnprocessableEntity, "IDEMPOTENCY_KEY_REUSED")

        client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            header(IDEMPOTENCY_KEY_HEADER, "key-1")
            setBody(InviteUserRequest("new@test.com", UserRole.STUDENT))
        }.assertProblem(HttpStatusCode.UnprocessableEntity, "IDEMPOTENCY_KEY_REUSED")
        assertEquals(1, lessonCount())
    }

    @Test
    fun `keys are per user`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val admin = getAdminToken(client)

        val mine = createLesson(client, teacher, lesson(), "shared-key")
        val theirs = createLesson(client, admin, lesson(scheduledAt = "2027-04-10T14:00:00+02:00"), "shared-key")

        assertEquals(HttpStatusCode.Created, mine.status)
        assertEquals(HttpStatusCode.Created, theirs.status)
        assertNull(theirs.headers[IDEMPOTENT_REPLAYED_HEADER])
        assertTrue(mine.body<LessonResponse>().id != theirs.body<LessonResponse>().id)
        assertEquals(2, lessonCount())
    }

    @Test
    fun `concurrent duplicates create one lesson`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val responses = coroutineScope {
            List(6) { async { createLesson(client, token, lesson(), "race") } }.awaitAll()
        }

        assertEquals(1, lessonCount())
        val created = responses.filter { it.status == HttpStatusCode.Created }
        assertTrue(created.isNotEmpty())
        assertEquals(1, created.map { it.body<LessonResponse>().id }.distinct().size)
        responses.filter { it.status != HttpStatusCode.Created }
            .forEach { it.assertProblem(HttpStatusCode.Conflict, "IDEMPOTENCY_REQUEST_IN_PROGRESS") }
    }

    @Test
    fun `a failed request frees the key`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        assertEquals(HttpStatusCode.Created, createLesson(client, token, lesson(), null).status)

        createLesson(client, token, lesson(), "retry").assertProblem(HttpStatusCode.Conflict, "AVAILABILITY_OVERLAP")
        assertEquals(0, count("SELECT count(*) FROM idempotency_keys"))

        // Not stored, so the same key works for the corrected request.
        val fixed = createLesson(client, token, lesson(scheduledAt = "2027-04-10T14:00:00+02:00"), "retry")
        assertEquals(HttpStatusCode.Created, fixed.status)
    }

    @Test
    fun `invite replays instead of INVITATION_ALREADY_PENDING`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        suspend fun invite() = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            header(IDEMPOTENCY_KEY_HEADER, "invite-1")
            setBody(InviteUserRequest("new@test.com", UserRole.STUDENT))
        }

        val first = invite()
        val second = invite()

        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals(1, count("SELECT count(*) FROM registrations WHERE email = 'new@test.com'"))
        assertEquals(1, sentEmails.sent.size)
    }

    @Test
    fun `invalid key is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        createLesson(client, token, lesson(), "x".repeat(256))
            .assertProblem(HttpStatusCode.BadRequest, "IDEMPOTENCY_KEY_INVALID")
        assertEquals(0, lessonCount())
    }

    @Test
    fun `expired keys are reclaimed and cleaned up`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        assertEquals(HttpStatusCode.Created, createLesson(client, token, lesson(), "old").status)
        transaction { exec("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute'") }

        // After 24h the key is free again, even for another payload.
        val reused = createLesson(client, token, lesson(scheduledAt = "2027-04-10T14:00:00+02:00"), "old")
        assertEquals(HttpStatusCode.Created, reused.status)
        assertNull(reused.headers[IDEMPOTENT_REPLAYED_HEADER])

        transaction { exec("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute'") }
        assertEquals(1, IdempotencyService().deleteExpired())
        assertEquals(0, count("SELECT count(*) FROM idempotency_keys"))
    }

    @Test
    fun `create routes document the header`() = testApp(
        mapOf("application.openapi.enabled" to "true", "swagger.allowed_hosts" to "localhost"),
    ) {
        val spec = client.get("/swagger/documentation.yaml").bodyAsText()

        // One header parameter per create route: lessons, assignments, goals, invites, lesson plans.
        assertEquals(5, Regex("name: \"?$IDEMPOTENCY_KEY_HEADER\"?\\s").findAll(spec).count(), spec.take(300))
    }
}

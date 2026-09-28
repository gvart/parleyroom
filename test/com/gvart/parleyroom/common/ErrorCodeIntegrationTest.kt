package com.gvart.parleyroom.common

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
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
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorCodeIntegrationTest : IntegrationTest() {

    private suspend fun HttpResponse.assertProblem(status: HttpStatusCode, code: String) {
        assertEquals(status, this.status)
        val problem = body<ProblemDetail>()
        assertEquals(code, problem.code)
        assertEquals(status.value, problem.status)
    }

    @Test
    fun `missing token returns UNAUTHORIZED problem body`() = testApp {
        val client = createJsonClient(this)

        client.get("/api/v1/users/me").assertProblem(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
    }

    @Test
    fun `wrong password returns INVALID_CREDENTIALS`() = testApp {
        val client = createJsonClient(this)

        client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("student@test.com", "wrong-password"))
        }.assertProblem(HttpStatusCode.Unauthorized, "INVALID_CREDENTIALS")
    }

    @Test
    fun `unknown lesson returns LESSON_NOT_FOUND`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        client.get("/api/v1/lessons/00000000-0000-0000-0000-000000000099") {
            bearerAuth(token)
        }.assertProblem(HttpStatusCode.NotFound, "LESSON_NOT_FOUND")
    }

    @Test
    fun `admin-only endpoint returns FORBIDDEN for students`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        client.get("/api/v1/admin/users") {
            bearerAuth(token)
        }.assertProblem(HttpStatusCode.Forbidden, "FORBIDDEN")
    }

    @Test
    fun `request validation failure returns VALIDATION_FAILED`() = testApp {
        val client = createJsonClient(this)

        client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("", ""))
        }.assertProblem(HttpStatusCode.BadRequest, "VALIDATION_FAILED")
    }

    @Test
    fun `malformed JSON returns MALFORMED_REQUEST`() = testApp {
        val client = createJsonClient(this)

        client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }.assertProblem(HttpStatusCode.BadRequest, "MALFORMED_REQUEST")
    }

    @Test
    fun `expired registration link returns REGISTRATION_LINK_EXPIRED`() = testApp {
        val client = createJsonClient(this)

        client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to "expired-registration-token",
                    "firstName" to "Expired",
                    "lastName" to "User",
                    "email" to "expired@test.com",
                    "password" to "password123",
                )
            )
        }.assertProblem(HttpStatusCode.BadRequest, "REGISTRATION_LINK_EXPIRED")
    }

    @Test
    fun `accepting a confirmed lesson returns LESSON_INVALID_STATE`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lessonId = createLesson(client, token).body<LessonResponse>().id

        client.post("/api/v1/lessons/$lessonId/accept") {
            bearerAuth(token)
        }.assertProblem(HttpStatusCode.BadRequest, "LESSON_INVALID_STATE")
    }

    @Test
    fun `overlapping lesson keeps AVAILABILITY_OVERLAP`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        createLesson(client, token)

        createLesson(client, token).assertProblem(HttpStatusCode.Conflict, "AVAILABILITY_OVERLAP")
    }

    private suspend fun createLesson(client: HttpClient, token: String): HttpResponse =
        client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = listOf(STUDENT_ID),
                    title = "German Lesson",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.parse("2027-04-10T10:00:00+02:00"),
                    durationMinutes = 60,
                    topic = "Conversation practice",
                )
            )
        }
}

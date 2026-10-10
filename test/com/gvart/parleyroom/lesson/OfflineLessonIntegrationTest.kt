package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.StartLessonResponse
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineLessonIntegrationTest : IntegrationTest() {

    private suspend fun createLesson(
        client: HttpClient,
        token: String,
        offline: Boolean,
        type: LessonType = LessonType.ONE_ON_ONE,
    ): HttpResponse = client.post("/api/v1/lessons") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateLessonRequest(
                teacherId = TEACHER_ID,
                studentIds = listOf(STUDENT_ID),
                title = "German Lesson",
                type = type,
                scheduledAt = OffsetDateTime.parse("2027-04-10T10:00:00+02:00"),
                topic = "Conversation practice",
                offline = offline,
            )
        )
    }

    private suspend fun setOffline(client: HttpClient, token: String, lessonId: String, offline: Boolean) =
        client.patch("/api/v1/lessons/$lessonId/content") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateLessonContentRequest(offline = offline))
        }

    @Test
    fun `lessons are online by default`() = testApp {
        val client = createJsonClient(this)
        val lesson = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(getTeacherToken(client))
            setBody(
                """{"teacherId":"$TEACHER_ID","studentIds":["$STUDENT_ID"],"title":"T","type":"ONE_ON_ONE",""" +
                    """"scheduledAt":"2027-04-10T10:00:00+02:00","topic":"t"}"""
            )
        }.body<LessonResponse>()

        assertFalse(lesson.offline)
    }

    @Test
    fun `teacher creates an offline club lesson`() = testApp {
        val client = createJsonClient(this)
        val response = createLesson(client, getTeacherToken(client), offline = true, type = LessonType.SPEAKING_CLUB)

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(response.body<LessonResponse>().offline)
    }

    @Test
    fun `student cannot request an offline lesson`() = testApp {
        val client = createJsonClient(this)
        val response = createLesson(client, getStudentToken(client), offline = true)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `starting an offline lesson opens no video room`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken, offline = true).body<LessonResponse>()

        val start = client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        assertEquals(HttpStatusCode.OK, start.status)
        assertNull(start.body<StartLessonResponse>().videoRoom)

        val completed = client.post("/api/v1/lessons/${lesson.id}/complete") { bearerAuth(teacherToken) }
        assertEquals(LessonStatus.COMPLETED, completed.body<LessonResponse>().status)
    }

    @Test
    fun `offline lesson has no video token`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken, offline = true).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = client.post("/api/v1/lessons/${lesson.id}/video-token") {
            bearerAuth(getStudentToken(client))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("LESSON_OFFLINE", response.body<ProblemDetail>().code)
    }

    @Test
    fun `teacher toggles offline before the lesson starts`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken, offline = false).body<LessonResponse>()

        val updated = setOffline(client, teacherToken, lesson.id, offline = true)

        assertEquals(HttpStatusCode.OK, updated.status)
        assertTrue(updated.body<LessonResponse>().offline)
    }

    @Test
    fun `offline cannot change once the lesson started`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createLesson(client, teacherToken, offline = true).body<LessonResponse>()
        client.post("/api/v1/lessons/${lesson.id}/start") { bearerAuth(teacherToken) }

        val response = setOffline(client, teacherToken, lesson.id, offline = false)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("LESSON_INVALID_STATE", response.body<ProblemDetail>().code)
    }

    @Test
    fun `student cannot toggle offline`() = testApp {
        val client = createJsonClient(this)
        val lesson = createLesson(client, getTeacherToken(client), offline = false).body<LessonResponse>()

        val response = setOffline(client, getStudentToken(client), lesson.id, offline = true)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}

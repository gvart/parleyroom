package com.gvart.parleyroom.document

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentSummary
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessonDocumentLinkIntegrationTest : IntegrationTest() {

    private suspend fun createLesson(
        client: HttpClient,
        token: String,
        type: LessonType = LessonType.ONE_ON_ONE,
        studentIds: List<String> = listOf(STUDENT_ID),
    ): LessonResponse = client.post("/api/v1/lessons") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateLessonRequest(
                teacherId = TEACHER_ID,
                studentIds = studentIds,
                title = "Lektion",
                type = type,
                scheduledAt = OffsetDateTime.now().plusDays(1),
                topic = "Haushalt",
                maxParticipants = if (type == LessonType.ONE_ON_ONE) null else 5,
            )
        )
    }.body()

    private suspend fun post(client: HttpClient, path: String, token: String, json: String): HttpResponse =
        client.post(path) { bearerAuth(token); setBody(TextContent(json, ContentType.Application.Json)) }

    private suspend fun getLesson(client: HttpClient, id: String, token: String) =
        client.get("/api/v1/lessons/$id") { bearerAuth(token) }.body<LessonResponse>()

    @Test
    fun `a document created from a lesson is linked and listed on it`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lesson = createLesson(client, token)

        val document = post(
            client, "/api/v1/documents", token,
            """{"title":"Nachbereitung","audience":"STUDENT","createdFromLessonId":"${lesson.id}","blocks":[]}""",
        ).body<DocumentResponse>()
        assertEquals(lesson.id, document.createdFromLessonId)
        assertEquals(listOf(lesson.id), document.lessonIds)

        val refs = getLesson(client, lesson.id, token).documents
        assertEquals(listOf(document.id to "Nachbereitung"), refs.map { it.id to it.title })
        val studentToken = getStudentToken(client)
        assertEquals(listOf(document.id), getLesson(client, lesson.id, studentToken).documents.map { it.id })

        val listed = client.get("/api/v1/lessons/${lesson.id}/documents") { bearerAuth(studentToken) }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertEquals(listOf(document.id), listed.body<List<DocumentSummary>>().map { it.id })
        val outsider = client.get("/api/v1/lessons/${lesson.id}/documents") { bearerAuth(getStudent2Token(client)) }
        assertEquals(HttpStatusCode.Forbidden, outsider.status)
    }

    @Test
    fun `teacher edits during the lesson bump the linked document's updatedAt`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val lesson = createLesson(client, token)
        val documentId = post(client, "/api/v1/documents", token, """{"title":"Live","audience":"STUDENT","blocks":[]}""")
            .body<DocumentResponse>().id
        assertEquals(HttpStatusCode.NoContent, post(client, "/api/v1/lessons/${lesson.id}/documents", token, """{"documentId":"$documentId"}""").status)
        // Linking twice is a no-op.
        assertEquals(HttpStatusCode.NoContent, post(client, "/api/v1/lessons/${lesson.id}/documents", token, """{"documentId":"$documentId"}""").status)

        val studentToken = getStudentToken(client)
        val before = getLesson(client, lesson.id, studentToken).documents.single().updatedAt

        val heading = """[{"id":"${UUID.randomUUID()}","type":"heading","text":"Neu","level":2}]"""
        client.put("/api/v1/documents/$documentId") {
            bearerAuth(token)
            setBody(TextContent("""{"title":"Live","audience":"STUDENT","blocks":$heading,"revision":1}""", ContentType.Application.Json))
        }

        val after = getLesson(client, lesson.id, studentToken).documents.single()
        assertTrue(after.updatedAt.isAfter(before))
        assertEquals(2, after.revision)
        val live = client.get("/api/v1/documents/$documentId") { bearerAuth(studentToken) }.body<DocumentResponse>()
        assertEquals("Neu", live.blocks.single().toString().substringAfter("\"text\":\"").substringBefore("\""))
    }

    @Test
    fun `students who have not joined a club do not see its documents`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val club = createLesson(client, token, type = LessonType.SPEAKING_CLUB, studentIds = emptyList())
        val documentId = post(client, "/api/v1/documents", token, """{"title":"Club","audience":"GROUP","blocks":[]}""")
            .body<DocumentResponse>().id
        post(client, "/api/v1/lessons/${club.id}/documents", token, """{"documentId":"$documentId"}""")

        val listed = client.get("/api/v1/lessons") { bearerAuth(getStudentToken(client)) }
            .body<LessonPageResponse>().lessons.single { it.id == club.id }
        assertEquals(emptyList(), listed.documents)
    }

    @Test
    fun `only the teacher's own lessons can be linked`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val documentId = post(client, "/api/v1/documents", token, """{"title":"Doc","audience":"LIBRARY","blocks":[]}""")
            .body<DocumentResponse>().id

        val response = post(client, "/api/v1/lessons/${UUID.randomUUID()}/documents", token, """{"documentId":"$documentId"}""")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("LESSON_NOT_FOUND", response.body<ProblemDetail>().code)

        val student = post(client, "/api/v1/lessons/${UUID.randomUUID()}/documents", getStudentToken(client), """{"documentId":"$documentId"}""")
        assertEquals(HttpStatusCode.Forbidden, student.status)
    }
}

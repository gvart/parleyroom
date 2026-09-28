package com.gvart.parleyroom.document

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.document.transfer.DocumentPageResponse
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.group.data.GroupType
import com.gvart.parleyroom.group.transfer.GroupRequest
import com.gvart.parleyroom.group.transfer.GroupResponse
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.seedStudentVocab
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStudentAccessIntegrationTest : IntegrationTest() {

    /** Answer keys from the fixture that appear nowhere else in it. */
    private val answerKeys = listOf(
        "\"solution\"", "correctOptionIds", "isTrue", "sampleAnswer",
        "Darum musst du dich kümmern", "du musst; sich kümmern", "Den Abwasch.", "Liebe Anna",
        "Soll ich heute die Blumen gießen?",
    )

    private suspend fun post(client: HttpClient, path: String, token: String, json: String): HttpResponse =
        client.post(path) { bearerAuth(token); setBody(TextContent(json, ContentType.Application.Json)) }

    private suspend fun createDocument(client: HttpClient, token: String, extra: String = ""): DocumentResponse =
        post(
            client, "/api/v1/documents", token,
            """{"title":"Hausaufgabe","audience":"STUDENT","blocks":${DocumentFixtures.allBlocks}$extra}""",
        ).body()

    private suspend fun studentGet(client: HttpClient, id: String, token: String) =
        client.get("/api/v1/documents/$id") { bearerAuth(token) }

    private suspend fun studentList(client: HttpClient, token: String) =
        client.get("/api/v1/documents") { bearerAuth(token) }.body<DocumentPageResponse>()

    private fun assertNoAnswerKeys(json: String) {
        answerKeys.forEach { key -> assertFalse(json.contains(key), "Student response leaks '$key'") }
    }

    @Test
    fun `a document that is not shared is invisible to students`() = testApp {
        val client = createJsonClient(this)
        val id = createDocument(client, getTeacherToken(client)).id
        val studentToken = getStudentToken(client)

        val response = studentGet(client, id, studentToken)
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("DOCUMENT_NOT_FOUND", response.body<ProblemDetail>().code)
        assertEquals(0, studentList(client, studentToken).total)
    }

    @Test
    fun `no answer key ever reaches a student`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val id = createDocument(client, teacherToken, extra = ""","studentIds":["$STUDENT_ID"]""").id

        // The teacher sees the answer keys.
        val teacherJson = client.get("/api/v1/documents/$id") { bearerAuth(teacherToken) }.bodyAsText()
        answerKeys.forEach { assertTrue(teacherJson.contains(it), "Teacher response misses '$it'") }

        val studentToken = getStudentToken(client)
        val response = studentGet(client, id, studentToken)
        assertEquals(HttpStatusCode.OK, response.status)
        val studentJson = response.bodyAsText()
        assertNoAnswerKeys(studentJson)
        val document = response.body<DocumentResponse>()
        assertEquals(13, document.blocks.size)
        assertEquals(emptyList(), document.studentIds)

        assertNoAnswerKeys(client.get("/api/v1/documents") { bearerAuth(studentToken) }.bodyAsText())
        assertEquals(1, studentList(client, studentToken).total)

        // Versions (full blocks) are teacher-only.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/documents/$id/versions") { bearerAuth(studentToken) }.status)

        // Other students still see nothing.
        assertEquals(HttpStatusCode.NotFound, studentGet(client, id, getStudent2Token(client)).status)
    }

    @Test
    fun `sharing with a group gives its members access and unshare revokes it`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val groupId = client.post("/api/v1/groups") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(GroupRequest(name = "Club", level = LanguageLevel.B1, type = GroupType.SPEECH, studentIds = listOf(STUDENT_ID)))
        }.body<GroupResponse>().id
        val id = createDocument(client, teacherToken).id
        val studentToken = getStudentToken(client)

        val shared = post(client, "/api/v1/documents/$id/share", teacherToken, """{"groupIds":["$groupId"]}""")
        assertEquals(listOf(groupId), shared.body<DocumentResponse>().groupIds)
        assertEquals(HttpStatusCode.OK, studentGet(client, id, studentToken).status)
        assertNoAnswerKeys(studentGet(client, id, studentToken).bodyAsText())

        post(client, "/api/v1/documents/$id/unshare", teacherToken, """{"groupIds":["$groupId"]}""")
        assertEquals(HttpStatusCode.NotFound, studentGet(client, id, studentToken).status)
    }

    @Test
    fun `share targets must belong to the teacher`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val id = createDocument(client, teacherToken).id

        val unlinked = post(client, "/api/v1/documents/$id/share", teacherToken, """{"studentIds":["$STUDENT_2_ID"]}""")
        assertEquals("STUDENT_NOT_LINKED", unlinked.body<ProblemDetail>().code)
        val unknownGroup = post(client, "/api/v1/documents/$id/share", teacherToken, """{"groupIds":["${UUID.randomUUID()}"]}""")
        assertEquals("GROUP_NOT_FOUND", unknownGroup.body<ProblemDetail>().code)
    }

    @Test
    fun `confirmed lesson participants read documents linked to the lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lessonId = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = listOf(STUDENT_ID),
                    title = "Lektion",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.now().plusDays(1),
                    topic = "Haushalt",
                )
            )
        }.body<LessonResponse>().id
        val id = createDocument(client, teacherToken).id
        val studentToken = getStudentToken(client)

        val link = post(client, "/api/v1/lessons/$lessonId/documents", teacherToken, """{"documentId":"$id"}""")
        assertEquals(HttpStatusCode.NoContent, link.status)
        assertEquals(HttpStatusCode.OK, studentGet(client, id, studentToken).status)
        assertEquals(listOf(id), client.get("/api/v1/documents?lessonId=$lessonId") { bearerAuth(studentToken) }
            .body<DocumentPageResponse>().documents.map { it.id })

        client.delete("/api/v1/lessons/$lessonId/documents/$id") { bearerAuth(teacherToken) }
        assertEquals(HttpStatusCode.NotFound, studentGet(client, id, studentToken).status)
    }

    @Test
    fun `students see vocab entries through their display setting`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val entryId = transaction {
            val studentVocabId = seedStudentVocab(UUID.fromString(STUDENT_ID), lemma = "Teekanne")
            StudentVocabTable.selectAll().where { StudentVocabTable.id eq studentVocabId }.single()[StudentVocabTable.vocabEntryId].value
        }
        val blocks = """[{"id":"${UUID.randomUUID()}","type":"vocab_table","rows":[{"id":"${UUID.randomUUID()}","vocabEntryId":"$entryId"}]}]"""
        val id = post(
            client, "/api/v1/documents", teacherToken,
            """{"title":"Wörter","audience":"STUDENT","studentIds":["$STUDENT_ID"],"blocks":$blocks}""",
        ).body<DocumentResponse>().id
        val studentToken = getStudentToken(client)

        // No level, no setting: Russian only, no reveal toggle -> the English translation is hidden.
        val hidden = studentGet(client, id, studentToken).body<DocumentResponse>().vocab.single()
        assertEquals(emptyMap(), hidden.translations)
        assertNull(hidden.revealTranslations)

        client.put("/api/v1/students/$STUDENT_ID/vocab-settings") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(VocabDisplaySetting(listOf("en"), allowTranslationToggle = false))
        }
        val shown = studentGet(client, id, studentToken).body<DocumentResponse>().vocab.single()
        assertEquals(mapOf("en" to "house"), shown.translations)
        assertEquals(listOf("en"), shown.display!!.fields)
    }
}

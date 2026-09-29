package com.gvart.parleyroom.homework

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.homework.transfer.AssignmentResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.HomeworkUploadResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeworkUploadIntegrationTest : IntegrationTest() {

    private suspend fun upload(
        client: HttpClient, token: String, homeworkId: String, itemId: String,
        contentType: String = "audio/webm;codecs=opus", bytes: ByteArray = "OggS-audio".toByteArray(), fileName: String = "aufnahme.webm",
    ): HttpResponse = client.submitFormWithBinaryData(
        url = "/api/v1/homework/$homeworkId/items/$itemId/uploads",
        formData = formData {
            append(
                "file",
                InputProvider(bytes.size.toLong()) { ByteReadPacket(bytes) },
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, contentType)
                },
            )
        },
    ) { bearerAuth(token) }

    private suspend fun HttpResponse.code(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    /** An AUDIO task and a TEXT task for the test student. */
    private suspend fun assign(client: HttpClient, teacher: String): AssignmentResponse {
        val response = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "Sprechen", "studentIds": ["$STUDENT_ID"], "items": [
                 { "kind": "TASK", "title": "Nimm dich auf", "responseType": "AUDIO" },
                 { "kind": "TASK", "title": "Schreib", "responseType": "TEXT" } ] }""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun saveAnswer(client: HttpClient, token: String, homeworkId: String, itemId: String, answer: String) =
        client.put("/api/v1/homework/$homeworkId/answers") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{ "answers": [{ "assignmentItemId": "$itemId", "answer": $answer }] }""")
        }

    @Test
    fun `student records audio, lists it in the answer, teacher can follow and download it`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assign(client, teacher)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        val audioItem = assignment.items[0].id

        val response = upload(client, student, id, audioItem)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val uploaded = response.body<HomeworkUploadResponse>()
        assertEquals("audio/webm", uploaded.contentType)
        assertEquals("/api/v1/homework/$id/uploads/${uploaded.id}/file", uploaded.downloadUrl)

        val saved = saveAnswer(client, student, id, audioItem, """{ "uploadIds": ["${uploaded.id}"] }""")
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())

        assertContentEquals("OggS-audio".toByteArray(), client.get(uploaded.downloadUrl) { bearerAuth(student) }.bodyAsBytes())
        // The teacher follows the work in progress.
        assertEquals(HttpStatusCode.OK, client.get(uploaded.downloadUrl) { bearerAuth(teacher) }.status)
        assertEquals(1, client.get("/api/v1/homework/$id") { bearerAuth(teacher) }.body<HomeworkResponse>().uploads.size)

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/homework/$id/submit") { bearerAuth(student) }.status)
        val download = client.get(uploaded.downloadUrl) { bearerAuth(teacher) }
        assertEquals(HttpStatusCode.OK, download.status)
        assertContentEquals("OggS-audio".toByteArray(), download.bodyAsBytes())
        assertEquals(1, client.get("/api/v1/homework/$id") { bearerAuth(teacher) }.body<HomeworkResponse>().uploads.size)

        // Locked after submit.
        assertEquals("SUBMISSION_LOCKED", upload(client, student, id, audioItem).code())
        assertEquals(HttpStatusCode.NotFound, client.get(uploaded.downloadUrl) { bearerAuth(getStudent2Token(client)) }.status)
    }

    @Test
    fun `content types are checked per response type`() = testApp {
        val client = createJsonClient(this)
        val student = getStudentToken(client)
        val assignment = assign(client, getTeacherToken(client))
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)

        val image = upload(client, student, id, assignment.items[0].id, contentType = "image/png")
        assertEquals(HttpStatusCode.BadRequest, image.status)
        assertEquals("UPLOAD_TYPE_NOT_ALLOWED", image.code())

        val onText = upload(client, student, id, assignment.items[1].id)
        assertEquals("UPLOAD_TYPE_NOT_ALLOWED", onText.code())

        val unknownItem = upload(client, student, id, STUDENT_ID)
        assertEquals("HOMEWORK_ITEM_INVALID", unknownItem.code())
    }

    @Test
    fun `size and count limits`() = testApp(extraConfig = mapOf("storage.max_file_size" to "16")) {
        val client = createJsonClient(this)
        val student = getStudentToken(client)
        val assignment = assign(client, getTeacherToken(client))
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        val item = assignment.items[0].id

        val tooLarge = upload(client, student, id, item, bytes = ByteArray(17))
        assertEquals(HttpStatusCode.BadRequest, tooLarge.status)
        assertEquals("FILE_TOO_LARGE", tooLarge.code())

        repeat(5) { assertEquals(HttpStatusCode.Created, upload(client, student, id, item).status) }
        val sixth = upload(client, student, id, item)
        assertEquals(HttpStatusCode.Conflict, sixth.status)
        assertEquals("UPLOAD_LIMIT_REACHED", sixth.code())
    }

    @Test
    fun `only the assigned student uploads, deleting an upload removes it from the answer`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assign(client, teacher)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        val item = assignment.items[0].id

        assertEquals(HttpStatusCode.Forbidden, upload(client, teacher, id, item).status)
        assertEquals(HttpStatusCode.NotFound, upload(client, getStudent2Token(client), id, item).status)

        val uploaded = upload(client, student, id, item).body<HomeworkUploadResponse>()
        val bogus = saveAnswer(client, student, id, item, """{ "uploadIds": ["$STUDENT_ID"] }""")
        assertEquals("HOMEWORK_ANSWER_INVALID", bogus.code())

        saveAnswer(client, student, id, item, """{ "uploadIds": ["${uploaded.id}"] }""")
        val otherItem = assignment.items[1].id
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/homework/$id/items/$otherItem/uploads/${uploaded.id}") { bearerAuth(student) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/homework/$id/items/$item/uploads/${uploaded.id}") { bearerAuth(teacher) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/homework/$id/items/$item/uploads/${uploaded.id}") { bearerAuth(student) }.status)
        val view = client.get("/api/v1/homework/$id") { bearerAuth(student) }.body<HomeworkResponse>()
        assertEquals("""{"uploadIds":[]}""", view.units.first().answer.toString())
        assertEquals(0, view.answeredUnits)
        assertEquals(HttpStatusCode.NotFound, client.get(uploaded.downloadUrl) { bearerAuth(student) }.status)
    }

    @Test
    fun `a material item grants the student read access to the material`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val material = client.submitFormWithBinaryData(
            url = "/api/v1/materials",
            formData = formData {
                append("metadata", """{"name":"Hörtext","type":"AUDIO"}""", Headers.build { append(HttpHeaders.ContentType, "application/json") })
                append(
                    "file",
                    InputProvider(4) { ByteReadPacket("mp3!".toByteArray()) },
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"hoertext.mp3\"")
                        append(HttpHeaders.ContentType, "audio/mpeg")
                    },
                )
            },
        ) { bearerAuth(teacher) }
        assertEquals(HttpStatusCode.Created, material.status, material.bodyAsText())
        val materialId = Json.parseToJsonElement(material.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertTrue(client.get("/api/v1/materials/$materialId") { bearerAuth(student) }.status != HttpStatusCode.OK)

        val assigned = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "Hören", "studentIds": ["$STUDENT_ID"],
                 "items": [{ "kind": "MATERIAL", "materialId": "$materialId", "task": "Hör zu und fasse zusammen.", "responseType": "TEXT" }] }""")
        assertEquals(HttpStatusCode.Created, assigned.status, assigned.bodyAsText())
        val item = assigned.body<AssignmentResponse>().items.single()
        assertEquals("Hörtext", item.title)
        assertEquals("/api/v1/materials/$materialId/file", item.material?.downloadUrl)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/materials/$materialId") { bearerAuth(student) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/materials/$materialId/file") { bearerAuth(student) }.status)
        assertTrue(client.get("/api/v1/materials/$materialId") { bearerAuth(getStudent2Token(client)) }.status != HttpStatusCode.OK)
    }
}

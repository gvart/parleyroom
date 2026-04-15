package com.gvart.parleyroom.material

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialIntegrationTest : IntegrationTest() {

    private val json = Json { encodeDefaults = true }

    private suspend fun createLink(
        client: HttpClient,
        token: String,
        name: String = "Grammar Reference",
        studentId: String? = STUDENT_ID,
        url: String = "https://example.com/grammar.html",
    ): HttpResponse {
        val metadata = CreateMaterialRequest(
            name = name,
            type = MaterialType.LINK,
            studentId = studentId,
            url = url,
        )
        return client.submitFormWithBinaryData(
            url = "/api/v1/materials",
            formData = formData {
                append(
                    "metadata",
                    json.encodeToString(CreateMaterialRequest.serializer(), metadata),
                    Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            },
        ) {
            bearerAuth(token)
        }
    }

    private suspend fun createFile(
        client: HttpClient,
        token: String,
        name: String = "chapter-1.pdf",
        studentId: String? = STUDENT_ID,
        type: MaterialType = MaterialType.PDF,
        fileName: String = "chapter-1.pdf",
        fileContentType: String = "application/pdf",
        bytes: ByteArray = "pdf content here".toByteArray(),
    ): HttpResponse {
        val metadata = CreateMaterialRequest(
            name = name,
            type = type,
            studentId = studentId,
        )
        return client.submitFormWithBinaryData(
            url = "/api/v1/materials",
            formData = formData {
                append(
                    "metadata",
                    json.encodeToString(CreateMaterialRequest.serializer(), metadata),
                    Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
                append(
                    "file",
                    InputProvider(bytes.size.toLong()) { ByteReadPacket(bytes) },
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, fileContentType)
                    },
                )
            },
        ) {
            bearerAuth(token)
        }
    }

    // ---------- Create ----------

    @Test
    fun `teacher can create LINK material`() = testApp {
        val client = createJsonClient(this)
        val response = createLink(client, getTeacherToken(client))

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<MaterialResponse>()
        assertEquals("Grammar Reference", body.name)
        assertEquals(MaterialType.LINK, body.type)
        assertEquals(TEACHER_ID, body.teacherId)
        assertEquals(STUDENT_ID, body.studentId)
        assertEquals("https://example.com/grammar.html", body.downloadUrl)
        assertNull(body.fileSize)
        assertNull(body.contentType)
    }

    @Test
    fun `teacher can create PDF material with file upload`() = testApp {
        val client = createJsonClient(this)
        val bytes = "sample PDF body".toByteArray()
        val response = createFile(client, getTeacherToken(client), bytes = bytes)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<MaterialResponse>()
        assertEquals(MaterialType.PDF, body.type)
        assertEquals("application/pdf", body.contentType)
        assertEquals(bytes.size.toLong(), body.fileSize)
        assertNotNull(body.downloadUrl)
        assertEquals("/api/v1/materials/${body.id}/file", body.downloadUrl)
    }

    @Test
    fun `student cannot create material`() = testApp {
        val client = createJsonClient(this)
        val response = createLink(client, getStudentToken(client))
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher cannot create material for unrelated student`() = testApp {
        val client = createJsonClient(this)
        val response = createLink(client, getTeacherToken(client), studentId = STUDENT_2_ID)
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `LINK material without url returns 400`() = testApp {
        val client = createJsonClient(this)
        val metadata = CreateMaterialRequest(
            name = "Broken link",
            type = MaterialType.LINK,
            studentId = STUDENT_ID,
            url = null,
        )
        val response = client.submitFormWithBinaryData(
            url = "/api/v1/materials",
            formData = formData {
                append(
                    "metadata",
                    json.encodeToString(CreateMaterialRequest.serializer(), metadata),
                    Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            },
        ) {
            bearerAuth(getTeacherToken(client))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PDF material without file part returns 400`() = testApp {
        val client = createJsonClient(this)
        val metadata = CreateMaterialRequest(
            name = "Missing file",
            type = MaterialType.PDF,
            studentId = STUDENT_ID,
        )
        val response = client.submitFormWithBinaryData(
            url = "/api/v1/materials",
            formData = formData {
                append(
                    "metadata",
                    json.encodeToString(CreateMaterialRequest.serializer(), metadata),
                    Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            },
        ) {
            bearerAuth(getTeacherToken(client))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---------- Read ----------

    @Test
    fun `get material by id returns downloadUrl pointing at stream route`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createFile(client, teacherToken).body<MaterialResponse>()

        val response = client.get("/api/v1/materials/${created.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<MaterialResponse>()
        assertEquals(created.id, body.id)
        assertNotNull(body.downloadUrl)
    }

    @Test
    fun `teacher sees only their own materials in list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        createLink(client, teacherToken)
        createLink(client, teacherToken, name = "Second")

        val page = client.get("/api/v1/materials") {
            bearerAuth(teacherToken)
        }.body<MaterialPageResponse>()

        assertEquals(2, page.total)
        assertTrue(page.materials.all { it.teacherId == TEACHER_ID })
    }

    @Test
    fun `student sees materials assigned to them`() = testApp {
        val client = createJsonClient(this)
        createLink(client, getTeacherToken(client))

        val page = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()

        assertEquals(1, page.materials.size)
        assertEquals(STUDENT_ID, page.materials.single().studentId)
    }

    @Test
    fun `student without assignment sees empty list`() = testApp {
        val client = createJsonClient(this)
        createLink(client, getTeacherToken(client))

        val page = client.get("/api/v1/materials") {
            bearerAuth(getStudent2Token(client))
        }.body<MaterialPageResponse>()

        assertEquals(0, page.total)
    }

    @Test
    fun `material list pagination returns slice and total`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        repeat(3) { i -> createLink(client, teacherToken, name = "Item $i") }

        val page = client.get("/api/v1/materials?page=2&pageSize=2") {
            bearerAuth(teacherToken)
        }.body<MaterialPageResponse>()

        assertEquals(3, page.total)
        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(1, page.materials.size)
    }

    // ---------- Update ----------

    @Test
    fun `teacher can rename their material`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createLink(client, teacherToken).body<MaterialResponse>()

        val response = client.put("/api/v1/materials/${created.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateMaterialRequest(name = "Renamed"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Renamed", response.body<MaterialResponse>().name)
    }

    @Test
    fun `student cannot update a material`() = testApp {
        val client = createJsonClient(this)
        val created = createLink(client, getTeacherToken(client)).body<MaterialResponse>()

        val response = client.put("/api/v1/materials/${created.id}") {
            bearerAuth(getStudentToken(client))
            contentType(ContentType.Application.Json)
            setBody(UpdateMaterialRequest(name = "Hijacked"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ---------- Delete ----------

    @Test
    fun `teacher can delete their LINK material`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createLink(client, teacherToken).body<MaterialResponse>()

        val response = client.delete("/api/v1/materials/${created.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val afterGet = client.get("/api/v1/materials/${created.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NotFound, afterGet.status)
    }

    @Test
    fun `teacher can delete their file material`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createFile(client, teacherToken).body<MaterialResponse>()

        val response = client.delete("/api/v1/materials/${created.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `student cannot delete a material`() = testApp {
        val client = createJsonClient(this)
        val created = createLink(client, getTeacherToken(client)).body<MaterialResponse>()

        val response = client.delete("/api/v1/materials/${created.id}") {
            bearerAuth(getStudentToken(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `unauthenticated requests return 401`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/materials")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
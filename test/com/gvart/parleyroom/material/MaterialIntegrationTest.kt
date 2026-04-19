package com.gvart.parleyroom.material

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.material.data.MaterialSkill
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateFolderRequest
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialFolderResponse
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.ShareRequest
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
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

    // ---------- Helpers ----------

    private suspend fun createLink(
        client: HttpClient,
        token: String,
        name: String = "Grammar Reference",
        url: String = "https://example.com/grammar.html",
        folderId: String? = null,
        level: LanguageLevel? = null,
        skill: MaterialSkill? = null,
    ): HttpResponse {
        val metadata = CreateMaterialRequest(
            name = name,
            type = MaterialType.LINK,
            folderId = folderId,
            level = level,
            skill = skill,
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
        type: MaterialType = MaterialType.PDF,
        fileName: String = "chapter-1.pdf",
        fileContentType: String = "application/pdf",
        bytes: ByteArray = "pdf content here".toByteArray(),
        folderId: String? = null,
    ): HttpResponse {
        val metadata = CreateMaterialRequest(
            name = name,
            type = type,
            folderId = folderId,
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

    private suspend fun shareWith(
        client: HttpClient,
        token: String,
        materialId: String,
        studentId: String,
    ) {
        client.post("/api/v1/materials/$materialId/shares") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(listOf(studentId)))
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
        assertEquals("https://example.com/grammar.html", body.downloadUrl)
        assertNull(body.fileSize)
        assertNull(body.contentType)
        assertNull(body.folderId)
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
    fun `LINK material without url returns 400`() = testApp {
        val client = createJsonClient(this)
        val metadata = CreateMaterialRequest(
            name = "Broken link",
            type = MaterialType.LINK,
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
    fun `PDF material accepts file part before metadata part`() = testApp {
        val client = createJsonClient(this)
        val metadata = CreateMaterialRequest(
            name = "out-of-order.pdf",
            type = MaterialType.PDF,
        )
        val bytes = "pdf content here".toByteArray()
        val response = client.submitFormWithBinaryData(
            url = "/api/v1/materials",
            formData = formData {
                append(
                    "file",
                    InputProvider(bytes.size.toLong()) { ByteReadPacket(bytes) },
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"out-of-order.pdf\"")
                        append(HttpHeaders.ContentType, "application/pdf")
                    },
                )
                append(
                    "metadata",
                    json.encodeToString(CreateMaterialRequest.serializer(), metadata),
                    Headers.build { append(HttpHeaders.ContentType, "application/json") },
                )
            },
        ) {
            bearerAuth(getTeacherToken(client))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<MaterialResponse>()
        assertEquals("out-of-order.pdf", body.name)
        assertEquals(MaterialType.PDF, body.type)
    }

    @Test
    fun `PDF material without file part returns 400`() = testApp {
        val client = createJsonClient(this)
        val metadata = CreateMaterialRequest(
            name = "Missing file",
            type = MaterialType.PDF,
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

    @Test
    fun `student sees shared material in list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createLink(client, teacherToken).body<MaterialResponse>()
        shareWith(client, teacherToken, created.id, STUDENT_ID)

        val page = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()

        assertEquals(1, page.total)
        assertEquals(created.id, page.materials.single().id)
    }

    @Test
    fun `student without share sees empty list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        // Create and share with STUDENT only
        val created = createLink(client, teacherToken).body<MaterialResponse>()
        shareWith(client, teacherToken, created.id, STUDENT_ID)

        val page = client.get("/api/v1/materials") {
            bearerAuth(getStudent2Token(client))
        }.body<MaterialPageResponse>()

        assertEquals(0, page.total)
    }

    @Test
    fun `student can stream file for a shared material`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val bytes = "real pdf data".toByteArray()
        val created = createFile(client, teacherToken, bytes = bytes).body<MaterialResponse>()
        shareWith(client, teacherToken, created.id, STUDENT_ID)

        val response = client.get("/api/v1/materials/${created.id}/file") {
            bearerAuth(getStudentToken(client))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `student without share gets 403 on file stream`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createFile(client, teacherToken).body<MaterialResponse>()
        // Share with STUDENT, NOT with STUDENT_2
        shareWith(client, teacherToken, created.id, STUDENT_ID)

        val response = client.get("/api/v1/materials/${created.id}/file") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
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
    fun `teacher can set folderId and level on update`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createLink(client, teacherToken).body<MaterialResponse>()

        // Create a folder first
        val folder = client.post("/api/v1/material-folders") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(name = "My Folder"))
        }.body<MaterialFolderResponse>()

        val response = client.put("/api/v1/materials/${created.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateMaterialRequest(folderId = folder.id, level = LanguageLevel.B1))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<MaterialResponse>()
        assertEquals(folder.id, body.folderId)
        assertEquals(LanguageLevel.B1, body.level)
    }

    @Test
    fun `teacher can set skill on update`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createLink(client, teacherToken).body<MaterialResponse>()

        val response = client.put("/api/v1/materials/${created.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateMaterialRequest(skill = MaterialSkill.GRAMMAR))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(MaterialSkill.GRAMMAR, response.body<MaterialResponse>().skill)
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

    // ---------- Clear sub-resources ----------

    @Test
    fun `teacher can clear folder tag via DELETE sub-resource`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val folder = client.post("/api/v1/material-folders") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(name = "Folder to clear"))
        }.body<MaterialFolderResponse>()

        val created = createLink(client, teacherToken, folderId = folder.id).body<MaterialResponse>()
        assertNotNull(created.folderId)

        val response = client.delete("/api/v1/materials/${created.id}/folder") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<MaterialResponse>().folderId)
    }

    @Test
    fun `teacher can clear level tag via DELETE sub-resource`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createLink(client, teacherToken, level = LanguageLevel.A2).body<MaterialResponse>()
        assertEquals(LanguageLevel.A2, created.level)

        val response = client.delete("/api/v1/materials/${created.id}/level") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<MaterialResponse>().level)
    }

    @Test
    fun `teacher can clear skill tag via DELETE sub-resource`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val created = createLink(client, teacherToken, skill = MaterialSkill.LISTENING).body<MaterialResponse>()
        assertEquals(MaterialSkill.LISTENING, created.skill)

        val response = client.delete("/api/v1/materials/${created.id}/skill") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<MaterialResponse>().skill)
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

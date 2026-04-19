package com.gvart.parleyroom.material

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateFolderRequest
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.FolderTreeNode
import com.gvart.parleyroom.material.transfer.MaterialFolderResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.ShareRequest
import com.gvart.parleyroom.material.transfer.UpdateFolderRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterialFolderIntegrationTest : IntegrationTest() {

    private val json = Json { encodeDefaults = true }

    // ---------- Helpers ----------

    private suspend fun createFolderOk(
        client: HttpClient,
        token: String,
        name: String,
        parentFolderId: String? = null,
    ): MaterialFolderResponse {
        val response = client.post("/api/v1/material-folders") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(name = name, parentFolderId = parentFolderId))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun createLink(
        client: HttpClient,
        token: String,
        name: String = "Grammar Reference",
        folderId: String? = null,
    ): MaterialResponse {
        val metadata = CreateMaterialRequest(
            name = name,
            type = MaterialType.LINK,
            folderId = folderId,
            url = "https://example.com/ref.html",
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
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    // ---------- Create ----------

    @Test
    fun `teacher creates root folder with null parentFolderId and zero counts`() = testApp {
        val client = createJsonClient(this)
        val folder = createFolderOk(client, getTeacherToken(client), "Grammar")

        assertNull(folder.parentFolderId)
        assertEquals(TEACHER_ID, folder.teacherId)
        assertEquals("Grammar", folder.name)
        assertEquals(0, folder.materialCount)
        assertEquals(0, folder.childFolderCount)
        assertEquals(0, folder.sharedWithCount)
    }

    @Test
    fun `teacher creates nested folder under own folder`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val parent = createFolderOk(client, teacherToken, "Parent")
        val child = createFolderOk(client, teacherToken, "Child", parentFolderId = parent.id)

        assertEquals(parent.id, child.parentFolderId)
        assertEquals(TEACHER_ID, child.teacherId)
    }

    @Test
    fun `cannot create folder with empty name returns 400`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/material-folders") {
            bearerAuth(getTeacherToken(client))
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(name = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `duplicate name in same parent returns 409`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val parent = createFolderOk(client, teacherToken, "Parent")
        createFolderOk(client, teacherToken, "Child", parentFolderId = parent.id)

        val duplicate = client.post("/api/v1/material-folders") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(name = "Child", parentFolderId = parent.id))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
    }

    @Test
    fun `duplicate name check is case-insensitive`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        createFolderOk(client, teacherToken, "Grammar")

        val duplicate = client.post("/api/v1/material-folders") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(name = "GRAMMAR"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
    }

    @Test
    fun `duplicate name at root for same teacher returns 409`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        createFolderOk(client, teacherToken, "Root Folder")

        val duplicate = client.post("/api/v1/material-folders") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(CreateFolderRequest(name = "Root Folder"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
    }

    @Test
    fun `same name is allowed under different parents`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val parentA = createFolderOk(client, teacherToken, "Parent A")
        val parentB = createFolderOk(client, teacherToken, "Parent B")
        val childA = createFolderOk(client, teacherToken, "Shared Name", parentFolderId = parentA.id)
        val childB = createFolderOk(client, teacherToken, "Shared Name", parentFolderId = parentB.id)

        assertEquals(parentA.id, childA.parentFolderId)
        assertEquals(parentB.id, childB.parentFolderId)
    }

    // ---------- Update ----------

    @Test
    fun `rename folder works`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Old Name")

        val response = client.put("/api/v1/material-folders/${folder.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateFolderRequest(name = "New Name"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("New Name", response.body<MaterialFolderResponse>().name)
    }

    @Test
    fun `move folder via parentFolderId`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val parentA = createFolderOk(client, teacherToken, "Parent A")
        val parentB = createFolderOk(client, teacherToken, "Parent B")
        val child = createFolderOk(client, teacherToken, "Child", parentFolderId = parentA.id)

        val response = client.put("/api/v1/material-folders/${child.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateFolderRequest(parentFolderId = parentB.id))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(parentB.id, response.body<MaterialFolderResponse>().parentFolderId)
    }

    @Test
    fun `moveToRoot true unparents a folder`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val parent = createFolderOk(client, teacherToken, "Parent")
        val child = createFolderOk(client, teacherToken, "Child", parentFolderId = parent.id)
        assertEquals(parent.id, child.parentFolderId)

        val response = client.put("/api/v1/material-folders/${child.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateFolderRequest(moveToRoot = true))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<MaterialFolderResponse>().parentFolderId)
    }

    @Test
    fun `cannot move folder under itself returns 400`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Self")

        val response = client.put("/api/v1/material-folders/${folder.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateFolderRequest(parentFolderId = folder.id))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cannot move folder under its own descendant returns 400`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val root = createFolderOk(client, teacherToken, "Root")
        val child = createFolderOk(client, teacherToken, "Child", parentFolderId = root.id)

        val response = client.put("/api/v1/material-folders/${root.id}") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateFolderRequest(parentFolderId = child.id))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---------- Delete ----------

    @Test
    fun `delete empty folder returns 204`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "To Delete")

        val response = client.delete("/api/v1/material-folders/${folder.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `delete non-empty folder without cascade returns 409`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Non-empty")
        createLink(client, teacherToken, folderId = folder.id)

        val response = client.delete("/api/v1/material-folders/${folder.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `delete with cascade removes descendants and materials`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val root = createFolderOk(client, teacherToken, "Root Cascade")
        val child = createFolderOk(client, teacherToken, "Child", parentFolderId = root.id)
        createLink(client, teacherToken, name = "Mat in child", folderId = child.id)

        val response = client.delete("/api/v1/material-folders/${root.id}?cascade=true") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val afterGet = client.get("/api/v1/material-folders/${root.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NotFound, afterGet.status)
    }

    // ---------- List / Tree ----------

    @Test
    fun `list tree=true returns nested structure`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val root = createFolderOk(client, teacherToken, "Root Tree")
        createFolderOk(client, teacherToken, "Child A", parentFolderId = root.id)
        createFolderOk(client, teacherToken, "Child B", parentFolderId = root.id)

        val tree = client.get("/api/v1/material-folders?tree=true") {
            bearerAuth(teacherToken)
        }.body<List<FolderTreeNode>>()

        val rootNode = tree.single { it.folder.id == root.id }
        assertEquals(2, rootNode.children.size)
    }

    @Test
    fun `student sees only shared subtree in tree view`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folderA = createFolderOk(client, teacherToken, "Folder A")
        val folderB = createFolderOk(client, teacherToken, "Folder B", parentFolderId = folderA.id)

        // Share folder A with STUDENT
        client.post("/api/v1/material-folders/${folderA.id}/shares") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(listOf(STUDENT_ID)))
        }

        val studentTree = client.get("/api/v1/material-folders?tree=true") {
            bearerAuth(getStudentToken(client))
        }.body<List<FolderTreeNode>>()

        // STUDENT sees A as the virtual root with B as child
        assertEquals(1, studentTree.size)
        assertEquals(folderA.id, studentTree.single().folder.id)
        val childIds = studentTree.single().children.map { it.folder.id }
        assertTrue(childIds.contains(folderB.id))

        // STUDENT_2 sees nothing — no shares
        val student2Tree = client.get("/api/v1/material-folders?tree=true") {
            bearerAuth(getStudent2Token(client))
        }.body<List<FolderTreeNode>>()
        assertTrue(student2Tree.isEmpty())
    }

    // NOTE: Cross-teacher isolation (teacher cannot nest under another teacher's folder)
    // cannot be tested here — there is only one TEACHER in test-data.sql.
    // The guard lives in MaterialFolderService.createFolder() at the parentUuid != null branch.
    // A second teacher fixture would be required to exercise it as an integration test.
}

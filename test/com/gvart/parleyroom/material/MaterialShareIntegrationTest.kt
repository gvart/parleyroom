package com.gvart.parleyroom.material

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateFolderRequest
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialFolderResponse
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.ShareListResponse
import com.gvart.parleyroom.material.transfer.ShareRequest
import com.gvart.parleyroom.material.transfer.BulkMaterialAction
import com.gvart.parleyroom.material.transfer.BulkMaterialRequest
import com.gvart.parleyroom.material.transfer.BulkMaterialResponse
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import kotlin.test.assertTrue

class MaterialShareIntegrationTest : IntegrationTest() {

    private val json = Json { encodeDefaults = true }

    // ---------- Helpers ----------

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

    private suspend fun shareMaterial(
        client: HttpClient,
        token: String,
        materialId: String,
        studentIds: List<String>,
    ): ShareListResponse {
        val response = client.post("/api/v1/materials/$materialId/shares") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(studentIds))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun shareFolder(
        client: HttpClient,
        token: String,
        folderId: String,
        studentIds: List<String>,
    ): ShareListResponse {
        val response = client.post("/api/v1/material-folders/$folderId/shares") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(studentIds))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun getStudentNotifications(client: HttpClient, token: String): NotificationPageResponse =
        client.get("/api/v1/notifications") {
            bearerAuth(token)
        }.body()

    // ---------- Material share tests ----------

    @Test
    fun `teacher shares material with student and list returns one grant`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val material = createLink(client, teacherToken)

        val shareList = shareMaterial(client, teacherToken, material.id, listOf(STUDENT_ID))

        assertEquals(1, shareList.grants.size)
        assertEquals(STUDENT_ID, shareList.grants.single().studentId)
        assertEquals(TEACHER_ID, shareList.grants.single().sharedBy)
    }

    @Test
    fun `sharing same student twice is idempotent and returns one grant`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val material = createLink(client, teacherToken)

        shareMaterial(client, teacherToken, material.id, listOf(STUDENT_ID))
        val shareList = shareMaterial(client, teacherToken, material.id, listOf(STUDENT_ID))

        assertEquals(1, shareList.grants.size)
    }

    @Test
    fun `revoke removes grant and list becomes empty`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val material = createLink(client, teacherToken)
        shareMaterial(client, teacherToken, material.id, listOf(STUDENT_ID))

        val revokeResponse = client.delete("/api/v1/materials/${material.id}/shares/$STUDENT_ID") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NoContent, revokeResponse.status)

        val listResponse = client.get("/api/v1/materials/${material.id}/shares") {
            bearerAuth(teacherToken)
        }.body<ShareListResponse>()
        assertTrue(listResponse.grants.isEmpty())
    }

    @Test
    fun `teacher cannot share with student they have no relationship with returns 403`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val material = createLink(client, teacherToken)

        // STUDENT_2 has no teacher_students relationship with TEACHER
        val response = client.post("/api/v1/materials/${material.id}/shares") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(listOf(STUDENT_2_ID)))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student cannot share a material returns 403`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val material = createLink(client, teacherToken)

        val response = client.post("/api/v1/materials/${material.id}/shares") {
            bearerAuth(getStudentToken(client))
            contentType(ContentType.Application.Json)
            setBody(ShareRequest(listOf(STUDENT_ID)))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `sharing material triggers MATERIAL_SHARED notification for the student`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val material = createLink(client, teacherToken)
        shareMaterial(client, teacherToken, material.id, listOf(STUDENT_ID))

        val notifications = getStudentNotifications(client, getStudentToken(client))
        val found = notifications.notifications.any {
            it.type == NotificationType.MATERIAL_SHARED && it.referenceId == material.id
        }
        assertTrue(found, "Expected MATERIAL_SHARED notification for student")
    }

    // ---------- Folder share cascade tests ----------

    @Test
    fun `folder share grants student access to materials inside the folder`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Shared Folder")
        val material = createLink(client, teacherToken, folderId = folder.id)
        shareFolder(client, teacherToken, folder.id, listOf(STUDENT_ID))

        val page = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()

        assertTrue(page.materials.any { it.id == material.id })

        // STUDENT_2 has no access
        val page2 = client.get("/api/v1/materials") {
            bearerAuth(getStudent2Token(client))
        }.body<MaterialPageResponse>()
        assertTrue(page2.materials.none { it.id == material.id })
    }

    @Test
    fun `revoking folder share removes student access to its materials`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Revoke Folder")
        val material = createLink(client, teacherToken, folderId = folder.id)
        shareFolder(client, teacherToken, folder.id, listOf(STUDENT_ID))

        // Verify student sees it
        val before = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()
        assertTrue(before.materials.any { it.id == material.id })

        // Revoke
        client.delete("/api/v1/material-folders/${folder.id}/shares/$STUDENT_ID") {
            bearerAuth(teacherToken)
        }

        val after = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()
        assertTrue(after.materials.none { it.id == material.id })
    }

    @Test
    fun `deep folder cascade gives access through nested hierarchy`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        // Folder A > Folder B > material M
        val folderA = createFolderOk(client, teacherToken, "Deep A")
        val folderB = createFolderOk(client, teacherToken, "Deep B", parentFolderId = folderA.id)
        val material = createLink(client, teacherToken, name = "Deep Material", folderId = folderB.id)

        // Share only the root (A)
        shareFolder(client, teacherToken, folderA.id, listOf(STUDENT_ID))

        val page = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()

        assertTrue(page.materials.any { it.id == material.id }, "Student should see material in nested subfolder")
    }

    @Test
    fun `moving material out of shared folder revokes folder-based access`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Move Out Folder")
        val material = createLink(client, teacherToken, folderId = folder.id)
        shareFolder(client, teacherToken, folder.id, listOf(STUDENT_ID))

        // Student can see it
        val before = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()
        assertTrue(before.materials.any { it.id == material.id })

        // Bulk MOVE to root (remove from folder)
        val bulkResponse = client.post("/api/v1/materials/bulk") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(BulkMaterialRequest(
                action = BulkMaterialAction.MOVE,
                materialIds = listOf(material.id),
                moveToRoot = true,
            ))
        }.body<BulkMaterialResponse>()
        assertEquals(1, bulkResponse.affected)

        // Student no longer sees the material (no direct share)
        val after = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()
        assertTrue(after.materials.none { it.id == material.id })
    }

    @Test
    fun `material directly shared and in shared folder retains access after move out of folder`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Combo Folder")
        val material = createLink(client, teacherToken, folderId = folder.id)

        // Both folder share AND direct share
        shareFolder(client, teacherToken, folder.id, listOf(STUDENT_ID))
        shareMaterial(client, teacherToken, material.id, listOf(STUDENT_ID))

        // Move out of folder
        client.post("/api/v1/materials/bulk") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(BulkMaterialRequest(
                action = BulkMaterialAction.MOVE,
                materialIds = listOf(material.id),
                moveToRoot = true,
            ))
        }

        // Student still sees the material because of direct share
        val page = client.get("/api/v1/materials") {
            bearerAuth(getStudentToken(client))
        }.body<MaterialPageResponse>()
        assertTrue(page.materials.any { it.id == material.id })
    }

    @Test
    fun `folder share triggers FOLDER_SHARED notification for the student`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val folder = createFolderOk(client, teacherToken, "Notify Folder")
        shareFolder(client, teacherToken, folder.id, listOf(STUDENT_ID))

        val notifications = getStudentNotifications(client, getStudentToken(client))
        val found = notifications.notifications.any {
            it.type == NotificationType.FOLDER_SHARED && it.referenceId == folder.id
        }
        assertTrue(found, "Expected FOLDER_SHARED notification for student")
    }

    @Test
    fun `bulk SHARE sends notifications for new grants and reports zero for duplicates`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val material = createLink(client, teacherToken, name = "Bulk Share")

        // First bulk share — should report affected=1 and trigger notification
        val first = client.post("/api/v1/materials/bulk") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(BulkMaterialRequest(
                action = BulkMaterialAction.SHARE,
                materialIds = listOf(material.id),
                studentIds = listOf(STUDENT_ID),
            ))
        }.body<BulkMaterialResponse>()
        assertEquals(1, first.affected)

        val notifs = getStudentNotifications(client, getStudentToken(client))
        assertTrue(notifs.notifications.any { it.type == NotificationType.MATERIAL_SHARED })

        // Second bulk share with same student — idempotent, affected=0
        val second = client.post("/api/v1/materials/bulk") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(BulkMaterialRequest(
                action = BulkMaterialAction.SHARE,
                materialIds = listOf(material.id),
                studentIds = listOf(STUDENT_ID),
            ))
        }.body<BulkMaterialResponse>()
        assertEquals(0, second.affected)
    }
}

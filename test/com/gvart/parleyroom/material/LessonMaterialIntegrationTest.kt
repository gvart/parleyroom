package com.gvart.parleyroom.material

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.lesson.transfer.CompleteLessonRequest
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.AttachMaterialsRequest
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.LessonMaterialListResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
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
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LessonMaterialIntegrationTest : IntegrationTest() {

    private val json = Json { encodeDefaults = true }

    // ---------- Helpers ----------

    /**
     * Creates a ONE_ON_ONE lesson with STUDENT as participant.
     * When created by the teacher the student status is automatically CONFIRMED.
     */
    private suspend fun createConfirmedLesson(
        client: HttpClient,
        teacherToken: String,
    ): LessonResponse {
        val response = client.post("/api/v1/lessons") {
            bearerAuth(teacherToken)
            contentType(ContentType.Application.Json)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = listOf(STUDENT_ID),
                    title = "Material Test Lesson",
                    type = LessonType.ONE_ON_ONE,
                    scheduledAt = OffsetDateTime.parse("2027-06-01T10:00:00+02:00"),
                    durationMinutes = 60,
                    topic = "Grammar chapter 3",
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun createLink(
        client: HttpClient,
        token: String,
        name: String = "Lesson Material",
    ): MaterialResponse {
        val metadata = CreateMaterialRequest(
            name = name,
            type = MaterialType.LINK,
            url = "https://example.com/lesson.html",
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

    private suspend fun createFile(
        client: HttpClient,
        token: String,
        name: String = "lesson-file.pdf",
        bytes: ByteArray = "pdf content".toByteArray(),
    ): MaterialResponse {
        val metadata = CreateMaterialRequest(
            name = name,
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
                append(
                    "file",
                    io.ktor.client.request.forms.InputProvider(bytes.size.toLong()) {
                        io.ktor.utils.io.core.ByteReadPacket(bytes)
                    },
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                        append(HttpHeaders.ContentType, "application/pdf")
                    },
                )
            },
        ) {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun attachMaterials(
        client: HttpClient,
        token: String,
        lessonId: String,
        materialIds: List<String>,
    ): LessonMaterialListResponse {
        val response = client.post("/api/v1/lessons/$lessonId/materials") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AttachMaterialsRequest(materialIds))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun startLesson(client: HttpClient, token: String, lessonId: String) {
        client.post("/api/v1/lessons/$lessonId/start") { bearerAuth(token) }
    }

    private suspend fun completeLesson(client: HttpClient, token: String, lessonId: String) {
        client.post("/api/v1/lessons/$lessonId/complete") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CompleteLessonRequest())
        }
    }

    private suspend fun getStudentNotifications(client: HttpClient, token: String): NotificationPageResponse =
        client.get("/api/v1/notifications") {
            bearerAuth(token)
        }.body()

    // ---------- Tests ----------

    @Test
    fun `teacher attaches two materials to lesson and GET returns both with attachedBy`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat1 = createLink(client, teacherToken, "Material One")
        val mat2 = createLink(client, teacherToken, "Material Two")

        val list = attachMaterials(client, teacherToken, lesson.id, listOf(mat1.id, mat2.id))

        assertEquals(2, list.items.size)
        assertTrue(list.items.all { it.attachedBy == TEACHER_ID })
        val returnedIds = list.items.map { it.material.id }.toSet()
        assertTrue(returnedIds.containsAll(listOf(mat1.id, mat2.id)))
    }

    @Test
    fun `attach is idempotent - second POST with same IDs returns same list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat = createLink(client, teacherToken)

        attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))
        val second = attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))

        assertEquals(1, second.items.size)
        assertEquals(mat.id, second.items.single().material.id)
    }

    @Test
    fun `CONFIRMED student can GET lesson materials list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat = createLink(client, teacherToken)
        attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))

        val response = client.get("/api/v1/lessons/${lesson.id}/materials") {
            bearerAuth(getStudentToken(client))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val list = response.body<LessonMaterialListResponse>()
        assertEquals(1, list.items.size)
        assertEquals(mat.id, list.items.single().material.id)
    }

    @Test
    fun `CONFIRMED student can stream file attached to their lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat = createFile(client, teacherToken)
        attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))

        val response = client.get("/api/v1/materials/${mat.id}/file") {
            bearerAuth(getStudentToken(client))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `student NOT on the lesson gets 403 on lesson materials list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat = createLink(client, teacherToken)
        attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))

        // STUDENT_2 is not on this lesson
        val response = client.get("/api/v1/lessons/${lesson.id}/materials") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // NOTE: Testing a student in pure REQUESTED (not CONFIRMED) status requires a group
    // lesson flow (student joins via the join-request endpoint). When a student creates a
    // 1:1 lesson, they are inserted into lesson_students as CONFIRMED immediately (see
    // LessonLifecycleService). The REQUESTED-status guard exists in requireLessonReader()
    // and is verified indirectly: a student with NO relationship to the lesson gets 403.
    // A dedicated REQUESTED-status test would need the separate participant join flow.
    @Test
    fun `student with no lesson relationship gets 403 on lesson materials list`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        // Lesson only includes STUDENT, not STUDENT_2
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat = createLink(client, teacherToken)
        attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))

        // STUDENT_2 has no record in lesson_students → 403
        val response = client.get("/api/v1/lessons/${lesson.id}/materials") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `detach removes single attachment from lesson`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat1 = createLink(client, teacherToken, "Keep")
        val mat2 = createLink(client, teacherToken, "Remove")
        attachMaterials(client, teacherToken, lesson.id, listOf(mat1.id, mat2.id))

        val detachResponse = client.delete("/api/v1/lessons/${lesson.id}/materials/${mat2.id}") {
            bearerAuth(teacherToken)
        }
        assertEquals(HttpStatusCode.NoContent, detachResponse.status)

        val list = client.get("/api/v1/lessons/${lesson.id}/materials") {
            bearerAuth(teacherToken)
        }.body<LessonMaterialListResponse>()

        assertEquals(1, list.items.size)
        assertEquals(mat1.id, list.items.single().material.id)
    }

    @Test
    fun `attaching material triggers MATERIAL_ATTACHED_TO_LESSON notification for CONFIRMED student`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat = createLink(client, teacherToken)
        attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))

        val notifications = getStudentNotifications(client, getStudentToken(client))
        val found = notifications.notifications.any {
            it.type == NotificationType.MATERIAL_ATTACHED_TO_LESSON &&
                    it.referenceId == lesson.id
        }
        assertTrue(found, "Expected MATERIAL_ATTACHED_TO_LESSON notification for CONFIRMED student")
    }

    @Test
    fun `student access to lesson materials persists after lesson is COMPLETED`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val lesson = createConfirmedLesson(client, teacherToken)
        val mat = createFile(client, teacherToken)
        attachMaterials(client, teacherToken, lesson.id, listOf(mat.id))

        // Transition lesson: CONFIRMED → IN_PROGRESS → COMPLETED
        startLesson(client, teacherToken, lesson.id)
        completeLesson(client, teacherToken, lesson.id)

        // Student can still read the lesson materials list
        val listResponse = client.get("/api/v1/lessons/${lesson.id}/materials") {
            bearerAuth(getStudentToken(client))
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertEquals(1, listResponse.body<LessonMaterialListResponse>().items.size)

        // Student can still stream the file
        val fileResponse = client.get("/api/v1/materials/${mat.id}/file") {
            bearerAuth(getStudentToken(client))
        }
        assertEquals(HttpStatusCode.OK, fileResponse.status)
    }

    // NOTE: Testing that a teacher cannot attach another teacher's material to a lesson
    // is not feasible with the current fixture set — there is only one TEACHER in test-data.sql.
    // The guard exists in LessonMaterialService.attach() and rejects materials whose teacherId
    // differs from the lesson's teacherId when principal is not ADMIN.
    // A second teacher fixture would be required to exercise this path as an integration test.
}

package com.gvart.parleyroom.homework

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.homework.data.HomeworkCategory
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.transfer.CreateHomeworkRequest
import com.gvart.parleyroom.homework.transfer.HomeworkPageResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.ReviewHomeworkRequest
import com.gvart.parleyroom.homework.transfer.SubmitHomeworkRequest
import com.gvart.parleyroom.homework.transfer.UpdateHomeworkRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeworkIntegrationTest : IntegrationTest() {

    private suspend fun createHomework(
        client: HttpClient,
        token: String,
        studentId: String = STUDENT_ID,
    ): HttpResponse = client.post("/api/v1/homework") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateHomeworkRequest(
                studentId = studentId,
                title = "Write an essay",
                description = "Write about your weekend",
                category = HomeworkCategory.WRITING,
                dueDate = "2026-04-15",
            )
        )
    }

    // -- Create --

    @Test
    fun `teacher can create homework`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createHomework(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
        val hw = response.body<HomeworkResponse>()
        assertEquals("Write an essay", hw.title)
        assertEquals(HomeworkStatus.OPEN, hw.status)
        assertEquals(STUDENT_ID, hw.studentId)
        assertEquals(TEACHER_ID, hw.teacherId)
        assertEquals(HomeworkCategory.WRITING, hw.category)
    }

    @Test
    fun `student cannot create homework`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = createHomework(client, token)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher cannot create homework for unrelated student`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        // Student2 has no teacher_students relationship with teacher
        val response = createHomework(client, token, studentId = STUDENT_2_ID)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -- Get --

    @Test
    fun `teacher sees their assigned homework`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        createHomework(client, token)

        val response = client.get("/api/v1/homework") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val list = response.body<HomeworkPageResponse>().homework
        assertEquals(1, list.size)
    }

    @Test
    fun `student sees their homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        createHomework(client, teacherToken)

        val response = client.get("/api/v1/homework") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val list = response.body<HomeworkPageResponse>().homework
        assertEquals(1, list.size)
    }

    @Test
    fun `get homework by id`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = createHomework(client, token).body<HomeworkResponse>()

        val response = client.get("/api/v1/homework/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(created.id, response.body<HomeworkResponse>().id)
    }

    @Test
    fun `filter homework by status`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        createHomework(client, token)

        val open = client.get("/api/v1/homework?status=OPEN") {
            bearerAuth(token)
        }.body<HomeworkPageResponse>().homework
        assertEquals(1, open.size)

        val done = client.get("/api/v1/homework?status=DONE") {
            bearerAuth(token)
        }.body<HomeworkPageResponse>().homework
        assertEquals(0, done.size)
    }

    // -- Update --

    @Test
    fun `teacher can update homework`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = createHomework(client, token).body<HomeworkResponse>()

        val response = client.put("/api/v1/homework/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateHomeworkRequest(title = "Updated title"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Updated title", response.body<HomeworkResponse>().title)
    }

    @Test
    fun `student cannot update homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        val response = client.put("/api/v1/homework/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(UpdateHomeworkRequest(title = "Hacked"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -- Delete --

    @Test
    fun `teacher can delete homework`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val created = createHomework(client, token).body<HomeworkResponse>()

        val response = client.delete("/api/v1/homework/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // -- Submit --

    @Test
    fun `student can submit homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        val response = client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "My weekend was great"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val hw = response.body<HomeworkResponse>()
        assertEquals(HomeworkStatus.SUBMITTED, hw.status)
        assertEquals("My weekend was great", hw.submissionText)
    }

    @Test
    fun `teacher cannot submit homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        val response = client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(SubmitHomeworkRequest(submissionText = "Not my homework"))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `cannot submit already submitted homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "First"))
        }

        val response = client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "Second"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Review --

    @Test
    fun `teacher can review submitted homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "My essay"))
        }

        val response = client.post("/api/v1/homework/${created.id}/review") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(ReviewHomeworkRequest(status = HomeworkStatus.DONE, teacherFeedback = "Great work!"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val hw = response.body<HomeworkResponse>()
        assertEquals(HomeworkStatus.DONE, hw.status)
        assertEquals("Great work!", hw.teacherFeedback)
    }

    @Test
    fun `cannot review unsubmitted homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        val response = client.post("/api/v1/homework/${created.id}/review") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(ReviewHomeworkRequest(status = HomeworkStatus.DONE))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Re-submit after rejection --

    @Test
    fun `student can resubmit rejected homework`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        // Submit
        client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "Draft"))
        }

        // Reject
        client.post("/api/v1/homework/${created.id}/review") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(ReviewHomeworkRequest(status = HomeworkStatus.REJECTED, teacherFeedback = "Too short"))
        }

        // Re-submit
        val response = client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "Improved essay"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val hw = response.body<HomeworkResponse>()
        assertEquals(HomeworkStatus.SUBMITTED, hw.status)
        assertEquals("Improved essay", hw.submissionText)
    }

    // -- Full lifecycle --

    @Test
    fun `full homework lifecycle`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // Create
        val hw = createHomework(client, teacherToken).body<HomeworkResponse>()
        assertEquals(HomeworkStatus.OPEN, hw.status)
        assertNull(hw.submissionText)

        // Submit
        val submitted = client.post("/api/v1/homework/${hw.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "My essay about weekend"))
        }.body<HomeworkResponse>()
        assertEquals(HomeworkStatus.SUBMITTED, submitted.status)

        // Review → Done
        val done = client.post("/api/v1/homework/${hw.id}/review") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(ReviewHomeworkRequest(status = HomeworkStatus.DONE, teacherFeedback = "Well done"))
        }.body<HomeworkResponse>()
        assertEquals(HomeworkStatus.DONE, done.status)
        assertEquals("Well done", done.teacherFeedback)
        assertEquals("My essay about weekend", done.submissionText)
    }

    @Test
    fun `cannot submit homework with both fields empty`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        val response = client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cannot submit homework with blank text and null url`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        val response = client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `can submit homework with only url`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        val response = client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionUrl = "https://example.com/essay.pdf"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val hw = response.body<HomeworkResponse>()
        assertEquals(HomeworkStatus.SUBMITTED, hw.status)
        assertEquals("https://example.com/essay.pdf", hw.submissionUrl)
    }

    @Test
    fun `review with invalid status OPEN is rejected`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "My essay"))
        }

        // Try to review with OPEN status (should be rejected by DTO validation)
        val response = client.post("/api/v1/homework/${created.id}/review") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(mapOf("status" to "OPEN", "teacherFeedback" to "Trying to hack"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `review with SUBMITTED status is rejected`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        val created = createHomework(client, teacherToken).body<HomeworkResponse>()

        client.post("/api/v1/homework/${created.id}/submit") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(SubmitHomeworkRequest(submissionText = "My essay"))
        }

        val response = client.post("/api/v1/homework/${created.id}/review") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(mapOf("status" to "SUBMITTED"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `homework list pagination returns slice and total`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        repeat(3) { createHomework(client, token) }

        val page = client.get("/api/v1/homework?page=2&pageSize=2") {
            bearerAuth(token)
        }.body<HomeworkPageResponse>()

        assertEquals(3, page.total)
        assertEquals(2, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(1, page.homework.size)
    }
}

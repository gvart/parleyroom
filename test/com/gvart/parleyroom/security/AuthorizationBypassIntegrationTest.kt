package com.gvart.parleyroom.security

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.goal.transfer.CreateGoalRequest
import com.gvart.parleyroom.goal.transfer.GoalResponse
import com.gvart.parleyroom.goal.transfer.UpdateGoalProgressRequest
import com.gvart.parleyroom.goal.transfer.UpdateGoalRequest
import com.gvart.parleyroom.homework.data.HomeworkCategory
import com.gvart.parleyroom.homework.transfer.CreateHomeworkRequest
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.SubmitHomeworkRequest
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.vocabulary.data.VocabCategory
import com.gvart.parleyroom.vocabulary.transfer.CreateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.UpdateVocabularyWordRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabularyWordResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthorizationBypassIntegrationTest : IntegrationTest() {

    // ---------- Goals ----------

    @Test
    fun `student2 cannot GET another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsStudent(client)

        val response = client.get("/api/v1/goals/$goalId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot UPDATE another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsStudent(client)

        val response = client.put("/api/v1/goals/$goalId") {
            bearerAuth(getStudent2Token(client))
            contentType(ContentType.Application.Json)
            setBody(UpdateGoalRequest(description = "Hijacked"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot UPDATE PROGRESS of another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsStudent(client)

        val response = client.put("/api/v1/goals/$goalId/progress") {
            bearerAuth(getStudent2Token(client))
            contentType(ContentType.Application.Json)
            setBody(UpdateGoalProgressRequest(progress = 99))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot COMPLETE another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsStudent(client)

        val response = client.post("/api/v1/goals/$goalId/complete") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot ABANDON another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsStudent(client)

        val response = client.post("/api/v1/goals/$goalId/abandon") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot DELETE another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsStudent(client)

        val response = client.delete("/api/v1/goals/$goalId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ---------- Vocabulary ----------

    @Test
    fun `student2 cannot GET another student's vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val wordId = createVocabularyAsTeacher(client)

        val response = client.get("/api/v1/vocabulary/$wordId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot UPDATE another student's vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val wordId = createVocabularyAsTeacher(client)

        val response = client.put("/api/v1/vocabulary/$wordId") {
            bearerAuth(getStudent2Token(client))
            contentType(ContentType.Application.Json)
            setBody(UpdateVocabularyWordRequest(english = "hijacked"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot DELETE another student's vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val wordId = createVocabularyAsTeacher(client)

        val response = client.delete("/api/v1/vocabulary/$wordId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot REVIEW another student's vocabulary word`() = testApp {
        val client = createJsonClient(this)
        val wordId = createVocabularyAsTeacher(client)

        val response = client.post("/api/v1/vocabulary/$wordId/review") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ---------- Homework ----------

    @Test
    fun `student2 cannot GET another student's homework`() = testApp {
        val client = createJsonClient(this)
        val homeworkId = createHomeworkAsTeacher(client)

        val response = client.get("/api/v1/homework/$homeworkId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot SUBMIT another student's homework`() = testApp {
        val client = createJsonClient(this)
        val homeworkId = createHomeworkAsTeacher(client)

        val response = client.post("/api/v1/homework/$homeworkId/submit") {
            bearerAuth(getStudent2Token(client))
            contentType(ContentType.Application.Json)
            setBody(SubmitHomeworkRequest(submissionText = "hijacked"))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student2 cannot DELETE another student's homework`() = testApp {
        val client = createJsonClient(this)
        val homeworkId = createHomeworkAsTeacher(client)

        val response = client.delete("/api/v1/homework/$homeworkId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ---------- Role enforcement ----------

    @Test
    fun `student cannot invite a new user`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/registration/invite") {
            bearerAuth(getStudentToken(client))
            contentType(ContentType.Application.Json)
            setBody(InviteUserRequest(email = "newcomer@test.com", role = UserRole.STUDENT))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ---------- Helpers ----------

    private suspend fun createGoalAsStudent(client: HttpClient): String {
        val token = getStudentToken(client)
        val resp = client.post("/api/v1/goals") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(CreateGoalRequest(studentId = STUDENT_ID, description = "Owned by student", targetDate = "2026-12-01"))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return resp.body<GoalResponse>().id
    }

    private suspend fun createVocabularyAsTeacher(client: HttpClient): String {
        val token = getTeacherToken(client)
        val resp = client.post("/api/v1/vocabulary") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                CreateVocabularyWordRequest(
                    studentId = STUDENT_ID,
                    german = "Haus",
                    english = "house",
                    category = VocabCategory.NOUN,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return resp.body<VocabularyWordResponse>().id
    }

    private suspend fun createHomeworkAsTeacher(client: HttpClient): String {
        val token = getTeacherToken(client)
        val resp = client.post("/api/v1/homework") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                CreateHomeworkRequest(
                    studentId = STUDENT_ID,
                    title = "Read chapter 1",
                    category = HomeworkCategory.READING,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return resp.body<HomeworkResponse>().id
    }
}

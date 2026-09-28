package com.gvart.parleyroom.security

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.homework.HomeworkFixtures
import com.gvart.parleyroom.goal.transfer.GoalResponse
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import com.gvart.parleyroom.vocabulary.transfer.UpdateStudentVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
        val goalId = createGoalAsTeacher(client)

        val response = client.get("/api/v1/goals/$goalId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `student2 cannot UPDATE another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsTeacher(client)

        val response = client.patch("/api/v1/goals/$goalId") {
            bearerAuth(getStudent2Token(client))
            contentType(ContentType.Application.Json)
            setBody("""{ "note": "Hijacked" }""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `the student cannot UPDATE or DELETE their own goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsTeacher(client)
        val token = getStudentToken(client)

        val patch = client.patch("/api/v1/goals/$goalId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{ "status": "ACHIEVED" }""")
        }
        assertEquals(HttpStatusCode.Forbidden, patch.status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/goals/$goalId") { bearerAuth(token) }.status)
    }

    @Test
    fun `student2 cannot DELETE another student's goal`() = testApp {
        val client = createJsonClient(this)
        val goalId = createGoalAsTeacher(client)

        val response = client.delete("/api/v1/goals/$goalId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
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
            setBody(UpdateStudentVocabRequest(status = StudentVocabStatus.LEARNED))
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

    // Another student's homework is invisible (404), so ids cannot be probed.

    @Test
    fun `student2 cannot GET another student's homework`() = testApp {
        val client = createJsonClient(this)
        val homeworkId = createHomeworkAsTeacher(client)

        val response = client.get("/api/v1/homework/$homeworkId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `student2 cannot SUBMIT another student's homework`() = testApp {
        val client = createJsonClient(this)
        val homeworkId = createHomeworkAsTeacher(client)

        val response = client.post("/api/v1/homework/$homeworkId/submit") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `student2 cannot DELETE another student's homework`() = testApp {
        val client = createJsonClient(this)
        val homeworkId = createHomeworkAsTeacher(client)

        val response = client.delete("/api/v1/homework/$homeworkId") {
            bearerAuth(getStudent2Token(client))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
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

    private suspend fun createGoalAsTeacher(client: HttpClient): String {
        val resp = client.post("/api/v1/goals") {
            bearerAuth(getTeacherToken(client))
            contentType(ContentType.Application.Json)
            setBody("""{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "B1" }""")
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
                QuickAddVocabRequest(
                    entry = VocabEntryInput(lemma = "Haus", wordType = WordType.NOUN, translations = mapOf("en" to "house")),
                    studentIds = listOf(STUDENT_ID),
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        return client.get("/api/v1/vocabulary") { bearerAuth(token) }.body<StudentVocabPageResponse>().words.single().id
    }

    private suspend fun createHomeworkAsTeacher(client: HttpClient): String {
        val assignment = HomeworkFixtures.assignTextTask(client, getTeacherToken(client), STUDENT_ID)
        return HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
    }
}

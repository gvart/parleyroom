package com.gvart.parleyroom.goal

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.goal.data.GoalSetBy
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.transfer.CreateGoalRequest
import com.gvart.parleyroom.goal.transfer.GoalResponse
import com.gvart.parleyroom.goal.transfer.UpdateGoalProgressRequest
import com.gvart.parleyroom.goal.transfer.UpdateGoalRequest
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

class GoalIntegrationTest : IntegrationTest() {

    private suspend fun createGoal(
        client: HttpClient,
        token: String,
        studentId: String = STUDENT_ID,
        description: String = "Pass B1 exam",
    ): HttpResponse = client.post("/api/v1/goals") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(
            CreateGoalRequest(
                studentId = studentId,
                description = description,
                targetDate = "2026-06-01",
            )
        )
    }

    // -- Create --

    @Test
    fun `student can create a goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = createGoal(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
        val goal = response.body<GoalResponse>()
        assertEquals("Pass B1 exam", goal.description)
        assertEquals(GoalStatus.ACTIVE, goal.status)
        assertEquals(GoalSetBy.STUDENT, goal.setBy)
        assertEquals(0, goal.progress)
        assertEquals(STUDENT_ID, goal.studentId)
    }

    @Test
    fun `teacher can create a goal for their student`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createGoal(client, token)

        assertEquals(HttpStatusCode.Created, response.status)
        val goal = response.body<GoalResponse>()
        assertEquals(GoalSetBy.TEACHER, goal.setBy)
        assertEquals(TEACHER_ID, goal.teacherId)
    }

    @Test
    fun `student cannot create goal for another student`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = createGoal(client, token, studentId = STUDENT_2_ID)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -- Get --

    @Test
    fun `student sees their goals`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        createGoal(client, token, description = "Goal 1")
        createGoal(client, token, description = "Goal 2")

        val response = client.get("/api/v1/goals") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val goals = response.body<List<GoalResponse>>()
        assertEquals(2, goals.size)
    }

    @Test
    fun `get goal by id`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()

        val response = client.get("/api/v1/goals/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(created.id, response.body<GoalResponse>().id)
    }

    @Test
    fun `filter goals by status`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val goal = createGoal(client, token).body<GoalResponse>()
        client.post("/api/v1/goals/${goal.id}/complete") { bearerAuth(token) }

        createGoal(client, token, description = "Active goal")

        val active = client.get("/api/v1/goals?status=ACTIVE") {
            bearerAuth(token)
        }.body<List<GoalResponse>>()
        assertEquals(1, active.size)

        val completed = client.get("/api/v1/goals?status=COMPLETED") {
            bearerAuth(token)
        }.body<List<GoalResponse>>()
        assertEquals(1, completed.size)
    }

    // -- Update --

    @Test
    fun `student can update their goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()

        val response = client.put("/api/v1/goals/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateGoalRequest(description = "Pass B2 exam"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Pass B2 exam", response.body<GoalResponse>().description)
    }

    @Test
    fun `cannot update completed goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()
        client.post("/api/v1/goals/${created.id}/complete") { bearerAuth(token) }

        val response = client.put("/api/v1/goals/${created.id}") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateGoalRequest(description = "Changed"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Progress --

    @Test
    fun `student can update progress`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()

        val response = client.put("/api/v1/goals/${created.id}/progress") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateGoalProgressRequest(progress = 50))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(50, response.body<GoalResponse>().progress)
    }

    @Test
    fun `progress must be 0-100`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()

        val response = client.put("/api/v1/goals/${created.id}/progress") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(UpdateGoalProgressRequest(progress = 150))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Complete --

    @Test
    fun `student can complete a goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()

        val response = client.post("/api/v1/goals/${created.id}/complete") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val goal = response.body<GoalResponse>()
        assertEquals(GoalStatus.COMPLETED, goal.status)
        assertEquals(100, goal.progress)
    }

    @Test
    fun `cannot complete already completed goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()
        client.post("/api/v1/goals/${created.id}/complete") { bearerAuth(token) }

        val response = client.post("/api/v1/goals/${created.id}/complete") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Abandon --

    @Test
    fun `student can abandon a goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()

        val response = client.post("/api/v1/goals/${created.id}/abandon") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(GoalStatus.ABANDONED, response.body<GoalResponse>().status)
    }

    @Test
    fun `cannot abandon completed goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()
        client.post("/api/v1/goals/${created.id}/complete") { bearerAuth(token) }

        val response = client.post("/api/v1/goals/${created.id}/abandon") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -- Delete --

    @Test
    fun `student can delete their goal`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val created = createGoal(client, token).body<GoalResponse>()

        val response = client.delete("/api/v1/goals/${created.id}") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // -- Full lifecycle --

    @Test
    fun `full goal lifecycle`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val studentToken = getStudentToken(client)

        // Teacher creates goal
        val goal = createGoal(client, teacherToken).body<GoalResponse>()
        assertEquals(GoalStatus.ACTIVE, goal.status)
        assertEquals(GoalSetBy.TEACHER, goal.setBy)

        // Student updates progress
        val p25 = client.put("/api/v1/goals/${goal.id}/progress") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(UpdateGoalProgressRequest(progress = 25))
        }.body<GoalResponse>()
        assertEquals(25, p25.progress)

        val p75 = client.put("/api/v1/goals/${goal.id}/progress") {
            contentType(ContentType.Application.Json)
            bearerAuth(studentToken)
            setBody(UpdateGoalProgressRequest(progress = 75))
        }.body<GoalResponse>()
        assertEquals(75, p75.progress)

        // Student completes
        val completed = client.post("/api/v1/goals/${goal.id}/complete") {
            bearerAuth(studentToken)
        }.body<GoalResponse>()
        assertEquals(GoalStatus.COMPLETED, completed.status)
        assertEquals(100, completed.progress)
    }
}

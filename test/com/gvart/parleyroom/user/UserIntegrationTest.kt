package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.user.transfer.UserListResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserIntegrationTest : IntegrationTest() {

    @Test
    fun `admin sees all users`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        val response = client.get("/api/v1/users") {
            bearerAuth(adminToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<UserListResponse>()
        assertTrue(result.users.size >= 4)
    }

    @Test
    fun `teacher sees their invited students`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val response = client.get("/api/v1/users") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `student sees their teachers`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        val response = client.get("/api/v1/users") {
            bearerAuth(studentToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<UserListResponse>()
        // Student has a teacher_students relationship with the teacher
        assertTrue(result.users.isNotEmpty())
        assertTrue(result.users.any { it.id == TEACHER_ID })
    }

    @Test
    fun `student without teacher relationship sees empty list`() = testApp {
        val client = createJsonClient(this)
        val student2Token = getStudent2Token(client)

        val response = client.get("/api/v1/users") {
            bearerAuth(student2Token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<UserListResponse>()
        assertEquals(0, result.users.size)
    }

    @Test
    fun `users endpoint requires authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.get("/api/v1/users")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

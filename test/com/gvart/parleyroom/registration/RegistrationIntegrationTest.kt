package com.gvart.parleyroom.registration

import com.gvart.parleyroom.IntegrationTest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class RegistrationIntegrationTest : IntegrationTest() {

    @Test
    fun `admin can invite a student`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to "${Uuid.random()}@test.com", "role" to "STUDENT"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `admin can invite a teacher`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to "${Uuid.random()}@test.com", "role" to "TEACHER"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `admin can invite another admin`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to "admin2@test.com", "role" to "ADMIN"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `invite fails without authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "someone@test.com", "role" to "STUDENT"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `invite fails for existing user`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to "admin@admin.co", "role" to "STUDENT"))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `registration fails with invalid token`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to "invalid-token",
                    "firstName" to "John",
                    "lastName" to "Doe",
                    "email" to "john@test.com",
                    "password" to "password123"
                )
            )
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticationIntegrationTest : IntegrationTest() {

    @Test
    fun `authenticate with valid credentials returns token`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "admin@admin.co", "password" to "admin"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("token"))
    }

    @Test
    fun `authenticate with wrong password returns 401`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "admin@admin.co", "password" to "wrong"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticate with non-existent user returns 401`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "nobody@test.com", "password" to "password"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticate with empty fields returns 400`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "", "password" to ""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.toKotlinInstant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticationIntegrationTest : IntegrationTest() {

    @Test
    fun `authenticate with valid credentials returns access and refresh tokens`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("admin@test.com", TEST_PASSWORD))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AuthenticateResponse>()
        assertTrue(body.accessToken.isNotBlank())
        assertTrue(body.refreshToken.isNotBlank())
        assertEquals(3600, body.accessTokenExpiresIn)
    }

    @Test
    fun `authenticate persists a refresh token row for the user`() = testApp {
        val client = createJsonClient(this)
        login(client, "admin@test.com")

        val stored = transaction {
            RefreshTokenTable.selectAll()
                .where { RefreshTokenTable.userId eq UUID.fromString(ADMIN_ID) }
                .count()
        }
        assertEquals(1, stored)
    }

    @Test
    fun `authenticate replaces a previous refresh token for the same user`() = testApp {
        val client = createJsonClient(this)
        val first = login(client, "admin@test.com").refreshToken
        val second = login(client, "admin@test.com").refreshToken

        assertNotEquals(first, second)

        val refreshOldResp = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(first))
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshOldResp.status)
    }

    @Test
    fun `authenticate with wrong password returns 401`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("admin@test.com", "wrong"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticate with non-existent user returns 401`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("nobody@test.com", TEST_PASSWORD))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `authenticate with empty fields returns 400`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("", ""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `refresh with valid token returns new pair and rotates`() = testApp {
        val client = createJsonClient(this)
        val initial = login(client, "admin@test.com")

        val response = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(initial.refreshToken))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AuthenticateResponse>()
        assertTrue(body.accessToken.isNotBlank())
        assertTrue(body.refreshToken.isNotBlank())
        assertNotEquals(initial.refreshToken, body.refreshToken)
        assertNotEquals(initial.accessToken, body.accessToken)
    }

    @Test
    fun `refresh invalidates the previous refresh token`() = testApp {
        val client = createJsonClient(this)
        val initial = login(client, "admin@test.com")

        // First refresh — succeeds
        client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(initial.refreshToken))
        }

        // Replay the original token — must fail
        val replay = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(initial.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
    }

    @Test
    fun `refresh with unknown token returns 401`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest("not-a-real-token"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `refresh with empty token returns 400`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `refresh with expired token returns 401 and deletes the row`() = testApp {
        val client = createJsonClient(this)
        val initial = login(client, "admin@test.com")

        transaction {
            RefreshTokenTable.update({ RefreshTokenTable.userId eq UUID.fromString(ADMIN_ID) }) {
                it[expiresAt] = OffsetDateTime.now().minusMinutes(1)
            }
        }

        val response = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(initial.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        val remaining = transaction {
            RefreshTokenTable.selectAll()
                .where { RefreshTokenTable.userId eq UUID.fromString(ADMIN_ID) }
                .count()
        }
        assertEquals(0, remaining)
    }

    private suspend fun login(client: HttpClient, email: String): AuthenticateResponse =
        client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest(email, TEST_PASSWORD))
        }.body()
}
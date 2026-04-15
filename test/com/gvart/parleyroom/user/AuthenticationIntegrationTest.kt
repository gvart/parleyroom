package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.registration.transfer.ResetPasswordRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordResponse
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.LogoutRequest
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    @Test
    fun `logout with valid refresh token returns 204 and revokes token`() = testApp {
        val client = createJsonClient(this)
        val tokens = login(client, "admin@test.com")

        val response = client.delete("/api/v1/token") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest(tokens.refreshToken))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify the refresh token is revoked
        val refreshResponse = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(tokens.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshResponse.status)
    }

    @Test
    fun `logout with invalid refresh token returns 401`() = testApp {
        val client = createJsonClient(this)
        val tokens = login(client, "admin@test.com")

        val response = client.delete("/api/v1/token") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest("not-a-real-token"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `logout without authentication returns 401`() = testApp {
        val client = createJsonClient(this)

        val response = client.delete("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest("some-token"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `logout does not revoke another user's refresh token`() = testApp {
        val client = createJsonClient(this)
        val adminTokens = login(client, "admin@test.com")
        val teacherTokens = login(client, "teacher@test.com")

        // Admin tries to revoke teacher's refresh token
        val response = client.delete("/api/v1/token") {
            bearerAuth(adminTokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest(teacherTokens.refreshToken))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)

        // Teacher's token should still work
        val refreshResponse = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(teacherTokens.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, refreshResponse.status)
    }

    @Test
    fun `failed logins below threshold increment counter but do not lock`() = testApp {
        val client = createJsonClient(this)
        val adminId = UUID.fromString(ADMIN_ID)

        repeat(4) {
            val response = client.post("/api/v1/token") {
                contentType(ContentType.Application.Json)
                setBody(AuthenticateRequest("admin@test.com", "wrong"))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        val (count, lock) = transaction {
            val row = UserTable.selectAll().where { UserTable.id eq adminId }.single()
            row[UserTable.failedLoginAttempts] to row[UserTable.lockedUntil]
        }
        assertEquals(4, count)
        assertNull(lock)
    }

    @Test
    fun `fifth failed login locks account and blocks correct password`() = testApp {
        val client = createJsonClient(this)
        val adminId = UUID.fromString(ADMIN_ID)

        repeat(5) {
            client.post("/api/v1/token") {
                contentType(ContentType.Application.Json)
                setBody(AuthenticateRequest("admin@test.com", "wrong"))
            }
        }

        val (count, lock) = transaction {
            val row = UserTable.selectAll().where { UserTable.id eq adminId }.single()
            row[UserTable.failedLoginAttempts] to row[UserTable.lockedUntil]
        }
        assertEquals(0, count)
        assertNotNull(lock)
        assertTrue(lock.isAfter(OffsetDateTime.now()))

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("admin@test.com", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Account is locked. Try again later.", response.body<ProblemDetail>().detail)
    }

    @Test
    fun `expired lockout allows login and clears state`() = testApp {
        val client = createJsonClient(this)
        val adminId = UUID.fromString(ADMIN_ID)

        transaction {
            UserTable.update({ UserTable.id eq adminId }) {
                it[lockedUntil] = OffsetDateTime.now().minusMinutes(1)
                it[failedLoginAttempts] = 0
            }
        }

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("admin@test.com", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val (count, lock) = transaction {
            val row = UserTable.selectAll().where { UserTable.id eq adminId }.single()
            row[UserTable.failedLoginAttempts] to row[UserTable.lockedUntil]
        }
        assertEquals(0, count)
        assertNull(lock)
    }

    @Test
    fun `successful login resets failed attempts counter`() = testApp {
        val client = createJsonClient(this)
        val adminId = UUID.fromString(ADMIN_ID)

        repeat(4) {
            client.post("/api/v1/token") {
                contentType(ContentType.Application.Json)
                setBody(AuthenticateRequest("admin@test.com", "wrong"))
            }
        }

        val ok = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("admin@test.com", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        repeat(4) {
            client.post("/api/v1/token") {
                contentType(ContentType.Application.Json)
                setBody(AuthenticateRequest("admin@test.com", "wrong"))
            }
        }

        val (count, lock) = transaction {
            val row = UserTable.selectAll().where { UserTable.id eq adminId }.single()
            row[UserTable.failedLoginAttempts] to row[UserTable.lockedUntil]
        }
        assertEquals(4, count)
        assertNull(lock)
    }

    @Test
    fun `password reset clears lockout state`() = testApp {
        val client = createJsonClient(this)
        val adminId = UUID.fromString(ADMIN_ID)
        val newPassword = "new-password-123"

        transaction {
            UserTable.update({ UserTable.id eq adminId }) {
                it[lockedUntil] = OffsetDateTime.now().plusMinutes(10)
                it[failedLoginAttempts] = 0
            }
        }

        val adminToken = getAdminToken(client)
        val resetTokenResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        assertEquals(HttpStatusCode.Created, resetTokenResponse.status)
        val resetToken = resetTokenResponse.body<ResetPasswordResponse>().token

        val confirmResponse = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, newPassword))
        }
        assertEquals(HttpStatusCode.OK, confirmResponse.status)

        val authResponse = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("admin@test.com", newPassword))
        }
        assertEquals(HttpStatusCode.OK, authResponse.status)

        val (count, lock) = transaction {
            val row = UserTable.selectAll().where { UserTable.id eq adminId }.single()
            row[UserTable.failedLoginAttempts] to row[UserTable.lockedUntil]
        }
        assertEquals(0, count)
        assertNull(lock)
    }

    @Test
    fun `unknown email returns Invalid credentials not Account locked`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("nobody@test.com", TEST_PASSWORD))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Invalid credentials", response.body<ProblemDetail>().detail)
    }

    private suspend fun login(client: HttpClient, email: String): AuthenticateResponse =
        client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest(email, TEST_PASSWORD))
        }.body()
}
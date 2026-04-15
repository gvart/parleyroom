package com.gvart.parleyroom.registration

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.registration.data.PasswordResetTable
import com.gvart.parleyroom.registration.transfer.ResetPasswordRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordResponse
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordResetIntegrationTest : IntegrationTest() {
    
    @Test
    fun `admin user can request password reset for self`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/password-reset") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<ResetPasswordResponse>()
        assertTrue(body.token.isNotBlank())
    }

    @Test
    fun `student can request password reset for self`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.post("/api/v1/password-reset") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<ResetPasswordResponse>()
        assertTrue(body.token.isNotBlank())
    }

    @Test
    fun `password reset for self fails without authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/password-reset")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin can request password reset for another user`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/password-reset/$STUDENT_ID") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<ResetPasswordResponse>()
        assertTrue(body.token.isNotBlank())
    }

    @Test
    fun `teacher cannot request password reset for another user`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.post("/api/v1/password-reset/$STUDENT_ID") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student cannot request password reset for another user`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.post("/api/v1/password-reset/$ADMIN_ID") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `password reset for user fails without authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/password-reset/$STUDENT_ID")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `confirm password reset with valid token`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        // Request reset
        val resetResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        val resetToken = resetResponse.body<ResetPasswordResponse>().token

        // Confirm with new password
        val response = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, "newpassword123"))
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Verify new password works
        val loginToken = getToken(client, "admin@test.com", "newpassword123")
        assertTrue(loginToken.isNotBlank())
    }

    @Test
    fun `confirm password reset with invalid token returns 404`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest("00000000-0000-0000-0000-000000000000", "newpassword"))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `confirm password reset with used token returns 400`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        // Request and use a reset token
        val resetResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        val resetToken = resetResponse.body<ResetPasswordResponse>().token

        client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, "newpassword123"))
        }

        // Try to use it again
        val response = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, "anotherpassword"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)

        // Verify the token is marked as used in DB
        val used = transaction {
            PasswordResetTable.selectAll()
                .where { PasswordResetTable.token eq java.util.UUID.fromString(resetToken) }
                .single()[PasswordResetTable.used]
        }
        assertTrue(used)
    }

    @Test
    fun `creating new reset token invalidates old unused tokens`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        // Request first reset token
        val firstResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        assertEquals(HttpStatusCode.Created, firstResponse.status)
        val firstResetToken = firstResponse.body<ResetPasswordResponse>().token

        // Request second reset token
        val secondResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        assertEquals(HttpStatusCode.Created, secondResponse.status)
        val secondResetToken = secondResponse.body<ResetPasswordResponse>().token

        // Verify the first token is now marked as used
        val firstTokenUsed = transaction {
            PasswordResetTable.selectAll()
                .where { PasswordResetTable.token eq java.util.UUID.fromString(firstResetToken) }
                .single()[PasswordResetTable.used]
        }
        assertTrue(firstTokenUsed, "First token should be invalidated (marked as used)")

        // Verify the second token is still unused
        val secondTokenUsed = transaction {
            PasswordResetTable.selectAll()
                .where { PasswordResetTable.token eq java.util.UUID.fromString(secondResetToken) }
                .single()[PasswordResetTable.used]
        }
        assertTrue(!secondTokenUsed, "Second token should still be unused")

        // Verify the first token can no longer be used to reset password
        val confirmResponse = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(firstResetToken, "newpassword123"))
        }
        assertEquals(HttpStatusCode.BadRequest, confirmResponse.status)

        // Verify the second token can still be used
        val confirmResponse2 = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(secondResetToken, "newpassword456"))
        }
        assertEquals(HttpStatusCode.OK, confirmResponse2.status)
    }

    @Test
    fun `password reset invalidates all refresh tokens for the user`() = testApp {
        val client = createJsonClient(this)

        // Login to get a refresh token
        val loginResponse = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("admin@test.com", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val tokens = loginResponse.body<AuthenticateResponse>()

        // Verify refresh token exists
        val tokensBefore = transaction {
            RefreshTokenTable.selectAll()
                .where { RefreshTokenTable.userId eq UUID.fromString(ADMIN_ID) }
                .count()
        }
        assertEquals(1, tokensBefore)

        // Request and confirm password reset
        val adminToken = tokens.accessToken
        val resetResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        val resetToken = resetResponse.body<ResetPasswordResponse>().token

        val confirmResponse = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, "newpassword789"))
        }
        assertEquals(HttpStatusCode.OK, confirmResponse.status)

        // Verify all refresh tokens are deleted
        val tokensAfter = transaction {
            RefreshTokenTable.selectAll()
                .where { RefreshTokenTable.userId eq UUID.fromString(ADMIN_ID) }
                .count()
        }
        assertEquals(0, tokensAfter)

        // Verify the old refresh token no longer works
        val refreshResponse = client.post("/api/v1/token/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(tokens.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshResponse.status)
    }

    @Test
    fun `confirm password reset with short password returns 400`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        val resetResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        val resetToken = resetResponse.body<ResetPasswordResponse>().token

        val response = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, "short"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `confirm password reset with 8 char password succeeds`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        val resetResponse = client.post("/api/v1/password-reset") {
            bearerAuth(adminToken)
        }
        val resetToken = resetResponse.body<ResetPasswordResponse>().token

        val response = client.post("/api/v1/password-reset/confirm") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(resetToken, "exactly8"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
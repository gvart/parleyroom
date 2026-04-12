package com.gvart.parleyroom.registration

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.registration.data.PasswordResetTable
import com.gvart.parleyroom.registration.transfer.ResetPasswordRequest
import com.gvart.parleyroom.registration.transfer.ResetPasswordResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
}
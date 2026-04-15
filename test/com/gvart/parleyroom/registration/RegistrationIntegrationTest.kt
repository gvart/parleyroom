package com.gvart.parleyroom.registration

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.registration.service.RegistrationService
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
            setBody(mapOf("email" to "admin@test.com", "role" to "STUDENT"))
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

    @Test
    fun `invite stores hashed token in database`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)
        val email = "${Uuid.random()}@test.com"

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to email, "role" to "STUDENT"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val inviteResponse = response.body<InviteUserResponse>()

        // Verify the stored token is the SHA-256 hash, not the plaintext
        val storedToken = transaction {
            RegistrationTable.selectAll()
                .where { RegistrationTable.email eq email }
                .single()[RegistrationTable.token]
        }

        val expectedHash = RegistrationService.hashToken(inviteResponse.token)
        assertEquals(expectedHash, storedToken)
        assertNotEquals(inviteResponse.token, storedToken)
    }

    @Test
    fun `registration succeeds with valid hashed token`() = testApp {
        val client = createJsonClient(this)

        // Use the pre-seeded test data: plaintext is "valid-registration-token", stored as hash
        val response = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to "valid-registration-token",
                    "firstName" to "New",
                    "lastName" to "User",
                    "email" to "newuser@test.com",
                    "password" to "password123"
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `invite fails when pending invitation already exists`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)
        val email = "${Uuid.random()}@test.com"

        // First invite should succeed
        val response1 = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to email, "role" to "STUDENT"))
        }
        assertEquals(HttpStatusCode.Created, response1.status)

        // Second invite for same email should fail with Conflict
        val response2 = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to email, "role" to "STUDENT"))
        }
        assertEquals(HttpStatusCode.Conflict, response2.status)
    }

    @Test
    fun `registration creates teacher-student relationship when invited by teacher`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"

        // Teacher invites a student
        val inviteResponse = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(mapOf("email" to email, "role" to "STUDENT"))
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)
        val registrationToken = inviteResponse.body<InviteUserResponse>().token

        // Student registers
        val registerResponse = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to registrationToken,
                    "firstName" to "New",
                    "lastName" to "Student",
                    "email" to email,
                    "password" to "password123"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)

        // Verify teacher-student relationship was created
        val relationship = transaction {
            val newUser = UserTable.selectAll()
                .where { UserTable.email eq email }
                .single()

            TeacherStudentTable.selectAll()
                .where { TeacherStudentTable.studentId eq newUser[UserTable.id] }
                .singleOrNull()
        }

        assertTrue(relationship != null, "Teacher-student relationship should be created")
        assertEquals(
            java.util.UUID.fromString(TEACHER_ID),
            relationship[TeacherStudentTable.teacherId].value
        )
    }

    @Test
    fun `registration trims whitespace from names for initials`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val email = "${Uuid.random()}@test.com"

        // Admin invites a student
        val inviteResponse = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(mapOf("email" to email, "role" to "STUDENT"))
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)
        val registrationToken = inviteResponse.body<InviteUserResponse>().token

        // Register with whitespace-padded names
        val registerResponse = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to registrationToken,
                    "firstName" to "  John  ",
                    "lastName" to "  Doe  ",
                    "email" to email,
                    "password" to "password123"
                )
            )
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)

        // Verify initials are computed from trimmed names
        val user = transaction {
            UserTable.selectAll()
                .where { UserTable.email eq email }
                .single()
        }

        assertEquals("JD", user[UserTable.initials])
        assertEquals("John", user[UserTable.firstName])
        assertEquals("Doe", user[UserTable.lastName])
    }

    @Test
    fun `invite fails with invalid email format - no at sign`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to "invalidemail", "role" to "STUDENT"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invite fails with invalid email format - no domain`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to "user@", "role" to "STUDENT"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invite fails with invalid email format - no dot in domain`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(mapOf("email" to "user@domain", "role" to "STUDENT"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `registration with expired token fails`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to "expired-registration-token",
                    "firstName" to "Expired",
                    "lastName" to "User",
                    "email" to "expired@test.com",
                    "password" to "password123"
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `registration with used token fails`() = testApp {
        val client = createJsonClient(this)

        val response = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to "used-registration-token",
                    "firstName" to "Used",
                    "lastName" to "User",
                    "email" to "used@test.com",
                    "password" to "password123"
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `registration fails with short password`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val email = "${Uuid.random()}@test.com"

        // Admin invites a student
        val inviteResponse = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(mapOf("email" to email, "role" to "STUDENT"))
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)
        val registrationToken = inviteResponse.body<InviteUserResponse>().token

        // Register with short password (less than 8 chars)
        val response = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to registrationToken,
                    "firstName" to "John",
                    "lastName" to "Doe",
                    "email" to email,
                    "password" to "short"
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `registration succeeds with password of exactly 8 characters`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val email = "${Uuid.random()}@test.com"

        val inviteResponse = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(mapOf("email" to email, "role" to "STUDENT"))
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)
        val registrationToken = inviteResponse.body<InviteUserResponse>().token

        val response = client.post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to registrationToken,
                    "firstName" to "John",
                    "lastName" to "Doe",
                    "email" to email,
                    "password" to "exactly8"
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }
}
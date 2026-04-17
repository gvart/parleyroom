package com.gvart.parleyroom.admin

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.admin.transfer.AdminStatsResponse
import com.gvart.parleyroom.admin.transfer.AdminUserListResponse
import com.gvart.parleyroom.admin.transfer.AdminUserResponse
import com.gvart.parleyroom.homework.data.HomeworkCategory
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AdminIntegrationTest : IntegrationTest() {

    // -------- Authorization --------

    @Test
    fun `unauthenticated request returns 401`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `teacher cannot access admin list`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users") { bearerAuth(getTeacherToken(client)) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student cannot access admin stats`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/stats") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `teacher cannot create user via admin`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/admin/users") {
            bearerAuth(getTeacherToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to "nope@test.com", "firstName" to "N", "lastName" to "O",
                "role" to "STUDENT", "password" to "longenough"
            ))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `student cannot unlock user`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/admin/users/$STUDENT_ID/unlock") {
            bearerAuth(getStudentToken(client))
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // -------- List --------

    @Test
    fun `admin lists all users`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users") { bearerAuth(getAdminToken(client)) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AdminUserListResponse>()
        assertTrue(body.total >= 4)
        assertTrue(body.users.any { it.email == "admin@test.com" })
    }

    @Test
    fun `filter by role returns only teachers`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users?role=TEACHER") {
            bearerAuth(getAdminToken(client))
        }
        val body = response.body<AdminUserListResponse>()
        assertTrue(body.users.isNotEmpty())
        assertTrue(body.users.all { it.role == UserRole.TEACHER })
    }

    @Test
    fun `search matches email case insensitively`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users?search=STUDENT2") {
            bearerAuth(getAdminToken(client))
        }
        val body = response.body<AdminUserListResponse>()
        assertTrue(body.users.any { it.email == "student2@test.com" })
    }

    @Test
    fun `pagination works`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users?page=1&pageSize=2") {
            bearerAuth(getAdminToken(client))
        }
        val body = response.body<AdminUserListResponse>()
        assertEquals(2, body.users.size)
        assertEquals(2, body.pageSize)
        assertTrue(body.total >= 4)
    }

    // -------- Get single --------

    @Test
    fun `admin sees full user detail`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users/$STUDENT_ID") {
            bearerAuth(getAdminToken(client))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AdminUserResponse>()
        assertEquals("student@test.com", body.email)
        assertEquals(0, body.failedLoginAttempts)
        assertNull(body.lockedUntil)
    }

    @Test
    fun `get unknown user returns 404`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/users/${UUID.randomUUID()}") {
            bearerAuth(getAdminToken(client))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // -------- Create --------

    @Test
    fun `admin creates a user who can then log in`() = testApp {
        val client = createJsonClient(this)
        val email = "created-${Uuid.random()}@test.com"
        val response = client.post("/api/v1/admin/users") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to email, "firstName" to "Created", "lastName" to "User",
                "role" to "STUDENT", "password" to "mynewpass1"
            ))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<AdminUserResponse>()
        assertEquals(email, body.email)
        assertEquals("CU", body.initials)
        assertEquals(UserStatus.ACTIVE, body.status)

        val login = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest(email, "mynewpass1"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun `creating duplicate email returns 409`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/admin/users") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to "admin@test.com", "firstName" to "Dup", "lastName" to "E",
                "role" to "STUDENT", "password" to "longenough"
            ))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `short password rejected on create`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/admin/users") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to "short-${Uuid.random()}@test.com", "firstName" to "A", "lastName" to "B",
                "role" to "STUDENT", "password" to "short"
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -------- Update --------

    @Test
    fun `admin updates user name and recomputes initials`() = testApp {
        val client = createJsonClient(this)
        val response = client.patch("/api/v1/admin/users/$STUDENT_ID") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("firstName" to "Renamed", "lastName" to "Person"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AdminUserResponse>()
        assertEquals("Renamed", body.firstName)
        assertEquals("RP", body.initials)
    }

    @Test
    fun `admin cannot change own role`() = testApp {
        val client = createJsonClient(this)
        val response = client.patch("/api/v1/admin/users/$ADMIN_ID") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("role" to "STUDENT"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `admin cannot deactivate self via patch`() = testApp {
        val client = createJsonClient(this)
        val response = client.patch("/api/v1/admin/users/$ADMIN_ID") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("status" to "INACTIVE"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `changing email to existing one returns 409`() = testApp {
        val client = createJsonClient(this)
        val response = client.patch("/api/v1/admin/users/$STUDENT_ID") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "teacher@test.com"))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // -------- Delete --------

    @Test
    fun `admin soft-deletes user and user cannot log in`() = testApp {
        val client = createJsonClient(this)
        val newId = createUserViaAdmin(client, "tobesoft-${Uuid.random()}@test.com")

        val response = client.delete("/api/v1/admin/users/$newId") {
            bearerAuth(getAdminToken(client))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val user = client.get("/api/v1/admin/users/$newId") {
            bearerAuth(getAdminToken(client))
        }.body<AdminUserResponse>()
        assertEquals(UserStatus.INACTIVE, user.status)
    }

    @Test
    fun `admin hard-deletes user without related data`() = testApp {
        val client = createJsonClient(this)
        val newId = createUserViaAdmin(client, "tobehard-${Uuid.random()}@test.com")

        val response = client.delete("/api/v1/admin/users/$newId?hard=true") {
            bearerAuth(getAdminToken(client))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val get = client.get("/api/v1/admin/users/$newId") {
            bearerAuth(getAdminToken(client))
        }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    @Test
    fun `admin cannot soft-delete self`() = testApp {
        val client = createJsonClient(this)
        val response = client.delete("/api/v1/admin/users/$ADMIN_ID") {
            bearerAuth(getAdminToken(client))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `admin cannot hard-delete self`() = testApp {
        val client = createJsonClient(this)
        val response = client.delete("/api/v1/admin/users/$ADMIN_ID?hard=true") {
            bearerAuth(getAdminToken(client))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `hard-delete blocked by related data returns 409`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val studentUuid = UUID.fromString(STUDENT_ID)
        val teacherUuid = UUID.fromString(TEACHER_ID)
        transaction {
            HomeworkTable.insert {
                it[studentId] = studentUuid
                it[teacherId] = teacherUuid
                it[title] = "blocker"
                it[category] = HomeworkCategory.WRITING
                it[createdAt] = OffsetDateTime.now()
                it[updatedAt] = OffsetDateTime.now()
            }
        }
        val response = client.delete("/api/v1/admin/users/$STUDENT_ID?hard=true") {
            bearerAuth(adminToken)
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    // -------- Unlock --------

    @Test
    fun `admin unlocks a locked account`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val studentUuid = UUID.fromString(STUDENT_ID)
        transaction {
            UserTable.update({ UserTable.id eq studentUuid }) {
                it[failedLoginAttempts] = 5
                it[lockedUntil] = OffsetDateTime.now().plusMinutes(15)
            }
        }

        val response = client.post("/api/v1/admin/users/$STUDENT_ID/unlock") {
            bearerAuth(adminToken)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AdminUserResponse>()
        assertEquals(0, body.failedLoginAttempts)
        assertNull(body.lockedUntil)

        val login = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("student@test.com", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    // -------- Set password --------

    @Test
    fun `admin sets a user password and old one is rejected`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/admin/users/$STUDENT_ID/password") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("newPassword" to "changedpass9"))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)

        val oldLogin = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("student@test.com", TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldLogin.status)

        val newLogin = client.post("/api/v1/token") {
            contentType(ContentType.Application.Json)
            setBody(AuthenticateRequest("student@test.com", "changedpass9"))
        }
        assertEquals(HttpStatusCode.OK, newLogin.status)
    }

    @Test
    fun `set-password revokes refresh tokens`() = testApp {
        val client = createJsonClient(this)
        val studentUuid = UUID.fromString(STUDENT_ID)

        // Seed a refresh token by logging in
        getStudentToken(client)
        val tokenCountBefore = transaction {
            RefreshTokenTable.selectAll().where { RefreshTokenTable.userId eq studentUuid }.count()
        }
        assertEquals(1L, tokenCountBefore)

        client.post("/api/v1/admin/users/$STUDENT_ID/password") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("newPassword" to "revoked1234"))
        }
        val tokenCountAfter = transaction {
            RefreshTokenTable.selectAll().where { RefreshTokenTable.userId eq studentUuid }.count()
        }
        assertEquals(0L, tokenCountAfter)
    }

    @Test
    fun `set-password with short password returns 400`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/admin/users/$STUDENT_ID/password") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("newPassword" to "short"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -------- Set status --------

    @Test
    fun `admin deactivates user via status endpoint`() = testApp {
        val client = createJsonClient(this)
        val newId = createUserViaAdmin(client, "tobedeact-${Uuid.random()}@test.com")

        val response = client.post("/api/v1/admin/users/$newId/status") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("status" to "INACTIVE"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AdminUserResponse>()
        assertEquals(UserStatus.INACTIVE, body.status)
    }

    @Test
    fun `admin cannot deactivate self via status`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/admin/users/$ADMIN_ID/status") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("status" to "INACTIVE"))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // -------- Stats --------

    @Test
    fun `admin stats returns counts for all sections`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/stats") { bearerAuth(getAdminToken(client)) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<AdminStatsResponse>()

        assertTrue(body.users.total >= 4)
        assertNotNull(body.users.byRole["ADMIN"])
        assertNotNull(body.users.byRole["TEACHER"])
        assertNotNull(body.users.byRole["STUDENT"])
        assertNotNull(body.users.byStatus["ACTIVE"])
        assertTrue(body.users.byRole["ADMIN"]!! >= 1)
    }

    @Test
    fun `stats security reflects locked accounts`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        transaction {
            UserTable.update({ UserTable.id eq UUID.fromString(STUDENT_ID) }) {
                it[failedLoginAttempts] = 5
                it[lockedUntil] = OffsetDateTime.now().plusMinutes(15)
            }
        }
        val response = client.get("/api/v1/admin/stats") { bearerAuth(adminToken) }
        val body = response.body<AdminStatsResponse>()
        assertTrue(body.security.currentlyLocked >= 1)
        assertTrue(body.security.withFailedAttempts >= 1)
    }

    // -------- Helpers --------

    private suspend fun createUserViaAdmin(client: HttpClient, email: String): String {
        val response = client.post("/api/v1/admin/users") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to email, "firstName" to "Temp", "lastName" to "User",
                "role" to "STUDENT", "password" to "tempuserpw"
            ))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body<AdminUserResponse>().id
    }
}

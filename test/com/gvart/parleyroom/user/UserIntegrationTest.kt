package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserListResponse
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.core.ByteReadPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `teacher sees their students via teacher-student relationship`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val response = client.get("/api/v1/users") {
            bearerAuth(teacherToken)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<UserListResponse>()
        // Teacher has a teacher_students relationship with student (not student2)
        assertTrue(result.users.isNotEmpty())
        assertTrue(result.users.any { it.id == STUDENT_ID })
        assertTrue(result.users.none { it.id == STUDENT_2_ID })
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

    @Test
    fun `GET me returns the authenticated user's profile`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.get("/api/v1/users/me") {
            bearerAuth(token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<UserResponse>()
        assertEquals(STUDENT_ID, body.id)
        assertEquals("student@test.com", body.email)
    }

    @Test
    fun `GET me requires authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.get("/api/v1/users/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PATCH me updates a single field`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(locale = "es"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<UserResponse>()
        assertEquals("es", body.locale)
    }

    @Test
    fun `PATCH me recomputes initials when name changes`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(firstName = "Alice", lastName = "Brown"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<UserResponse>()
        assertEquals("Alice", body.firstName)
        assertEquals("Brown", body.lastName)
        assertEquals("AB", body.initials)
    }

    @Test
    fun `PATCH me recomputes initials when only first name changes`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val before = client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>()

        val response = client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(firstName = "Zoe"))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<UserResponse>()
        assertEquals("Zoe", body.firstName)
        assertEquals("Z${before.lastName[0]}", body.initials)
    }

    @Test
    fun `PATCH me updates language level`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(level = LanguageLevel.C1))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<UserResponse>()
        assertEquals(LanguageLevel.C1, body.level)
    }

    @Test
    fun `PATCH me with no fields returns 400`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PATCH me with blank first name returns 400`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.patch("/api/v1/users/me") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(firstName = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `PATCH me requires authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.patch("/api/v1/users/me") {
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(locale = "fr"))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `PATCH me does not allow updating another user`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        client.patch("/api/v1/users/me") {
            bearerAuth(studentToken)
            contentType(ContentType.Application.Json)
            setBody(UpdateProfileRequest(firstName = "Hijack"))
        }

        // Verify teacher's profile was untouched
        val teacherToken = getTeacherToken(client)
        val teacherProfile = client.get("/api/v1/users/me") {
            bearerAuth(teacherToken)
        }.body<UserResponse>()
        assertNotNull(teacherProfile.firstName)
        assertTrue(teacherProfile.firstName != "Hijack")
    }

    private suspend fun uploadAvatar(
        client: HttpClient,
        token: String,
        fileName: String = "me.png",
        contentType: String = "image/png",
        bytes: ByteArray = pngHeader(),
    ): HttpResponse = client.submitFormWithBinaryData(
        url = "/api/v1/users/me/avatar",
        formData = formData {
            append(
                "file",
                InputProvider(bytes.size.toLong()) { ByteReadPacket(bytes) },
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, contentType)
                },
            )
        },
    ) { bearerAuth(token) }

    private fun pngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ) + ByteArray(32) { 0 }

    @Test
    fun `POST avatar uploads image and returns stream URL`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = uploadAvatar(client, token)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<UserResponse>()
        assertNotNull(body.avatarUrl)
        assertTrue(body.avatarUrl!!.startsWith("/api/v1/users/${body.id}/avatar"))
    }

    @Test
    fun `GET me reflects uploaded avatar`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        uploadAvatar(client, token)

        val profile = client.get("/api/v1/users/me") {
            bearerAuth(token)
        }.body<UserResponse>()
        assertNotNull(profile.avatarUrl)
    }

    @Test
    fun `POST avatar with non-image content type returns 400`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = uploadAvatar(
            client,
            token,
            fileName = "data.bin",
            contentType = "application/octet-stream",
            bytes = ByteArray(16) { 0 },
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST avatar larger than 5MB returns 400`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val bytes = ByteArray(5 * 1024 * 1024 + 1)
        val response = uploadAvatar(client, token, bytes = bytes)
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST avatar without file part returns 400`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.submitFormWithBinaryData(
            url = "/api/v1/users/me/avatar",
            formData = formData { },
        ) { bearerAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST avatar requires authentication`() = testApp {
        val client = createJsonClient(this)

        val response = client.submitFormWithBinaryData(
            url = "/api/v1/users/me/avatar",
            formData = formData {
                val bytes = pngHeader()
                append(
                    "file",
                    InputProvider(bytes.size.toLong()) { ByteReadPacket(bytes) },
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"me.png\"")
                        append(HttpHeaders.ContentType, "image/png")
                    },
                )
            },
        )

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `DELETE avatar clears avatarUrl`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        uploadAvatar(client, token)

        val response = client.delete("/api/v1/users/me/avatar") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<UserResponse>().avatarUrl)
    }

    @Test
    fun `DELETE avatar on user without avatar succeeds and returns null`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.delete("/api/v1/users/me/avatar") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<UserResponse>().avatarUrl)
    }

    @Test
    fun `uploading new avatar replaces the old one`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val first = uploadAvatar(client, token, fileName = "first.png").body<UserResponse>()
        val second = uploadAvatar(client, token, fileName = "second.png").body<UserResponse>()

        assertNotNull(first.avatarUrl)
        assertNotNull(second.avatarUrl)
        assertTrue(first.avatarUrl != second.avatarUrl)
    }

    @Test
    fun `users list pagination returns slice and total`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val page = client.get("/api/v1/users?page=1&pageSize=2") {
            bearerAuth(token)
        }.body<UserListResponse>()

        assertTrue(page.total >= 4)
        assertEquals(1, page.page)
        assertEquals(2, page.pageSize)
        assertEquals(2, page.users.size)
    }
}

package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.admin.transfer.AdminUserResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserListResponse
import com.gvart.parleyroom.user.transfer.UserResponse
import com.gvart.parleyroom.vocabulary.transfer.SetStudentLevelRequest
import com.gvart.parleyroom.vocabulary.transfer.SetStudentNativeLanguageRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabSettingsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class LocalizationIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.patchMe(token: String, body: Any): HttpResponse = patch("/api/v1/users/me") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpClient.me(token: String): UserResponse = get("/api/v1/users/me") { bearerAuth(token) }.body()

    /** Invites (as [inviterToken]) and registers a user; returns their access token. */
    private suspend fun HttpClient.inviteAndRegister(inviterToken: String, role: String, invite: Map<String, String> = emptyMap()): String {
        val email = "${Uuid.random()}@test.com"
        val token = post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json); bearerAuth(inviterToken)
            setBody(mapOf("email" to email, "role" to role) + invite)
        }.body<InviteUserResponse>().token
        val registered = post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("token" to token, "firstName" to "New", "lastName" to "User", "email" to email, "password" to TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.Created, registered.status)
        return getToken(this, email)
    }

    @Test
    fun `locale accepts ru, de and en and rejects others`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        listOf("ru", "de", "en").forEach { locale ->
            val response = client.patchMe(token, UpdateProfileRequest(locale = locale))
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(locale, response.body<UserResponse>().locale)
        }
        listOf("uk", "fr", "xx").forEach { locale ->
            val response = client.patchMe(token, UpdateProfileRequest(locale = locale))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("UNSUPPORTED_LOCALE", response.body<ProblemDetail>().code)
        }
        assertEquals("en", client.me(token).locale)
    }

    @Test
    fun `new users get locale ru, students native language ru, and no confirmed locale`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        val student = client.me(client.inviteAndRegister(adminToken, "STUDENT"))
        assertEquals("ru", student.locale)
        assertEquals("ru", student.nativeLanguage)
        assertNull(student.localeConfirmedAt)

        val teacher = client.me(client.inviteAndRegister(adminToken, "TEACHER"))
        assertEquals("ru", teacher.locale)
        assertNull(teacher.nativeLanguage)

        val created = client.post("/api/v1/admin/users") {
            bearerAuth(adminToken); contentType(ContentType.Application.Json)
            setBody(mapOf("email" to "${Uuid.random()}@test.com", "firstName" to "A", "lastName" to "B", "role" to "STUDENT", "password" to TEST_PASSWORD))
        }.body<AdminUserResponse>()
        assertEquals("ru", created.locale)
        assertEquals("ru", created.nativeLanguage)
    }

    @Test
    fun `confirmLocale sets localeConfirmedAt with the chosen locale`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        assertNull(client.me(token).localeConfirmedAt)

        val confirmed = client.patchMe(token, UpdateProfileRequest(locale = "de", confirmLocale = true)).body<UserResponse>()
        assertEquals("de", confirmed.locale)
        assertNotNull(confirmed.localeConfirmedAt)

        // confirmLocale alone is a valid update
        assertEquals(HttpStatusCode.OK, client.patchMe(token, UpdateProfileRequest(confirmLocale = true)).status)
        assertEquals(HttpStatusCode.BadRequest, client.patchMe(token, UpdateProfileRequest(confirmLocale = false)).status)
    }

    @Test
    fun `native language is set on invite and changed by the teacher and the student`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)

        val badInvite = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json); bearerAuth(teacherToken)
            setBody(mapOf("email" to "${Uuid.random()}@test.com", "role" to "STUDENT", "nativeLanguage" to "de"))
        }
        assertEquals("UNSUPPORTED_NATIVE_LANGUAGE", badInvite.body<ProblemDetail>().code)

        val studentToken = client.inviteAndRegister(teacherToken, "STUDENT", mapOf("nativeLanguage" to "uk"))
        val student = client.me(studentToken)
        assertEquals("uk", student.nativeLanguage)
        val listed = client.get("/api/v1/users?pageSize=100") { bearerAuth(teacherToken) }.body<UserListResponse>()
            .users.single { it.id == student.id }
        assertEquals("uk", listed.nativeLanguage)

        val byTeacher = client.put("/api/v1/students/${student.id}/native-language") {
            contentType(ContentType.Application.Json); bearerAuth(teacherToken); setBody(SetStudentNativeLanguageRequest("en"))
        }
        assertEquals(HttpStatusCode.OK, byTeacher.status)
        assertEquals("en", byTeacher.body<VocabSettingsResponse>().nativeLanguage)
        assertEquals("en", client.me(studentToken).nativeLanguage)

        val unsupported = client.put("/api/v1/students/${student.id}/native-language") {
            contentType(ContentType.Application.Json); bearerAuth(teacherToken); setBody(SetStudentNativeLanguageRequest("de"))
        }
        assertEquals("UNSUPPORTED_NATIVE_LANGUAGE", unsupported.body<ProblemDetail>().code)
        val notTheirTeacher = client.put("/api/v1/students/$STUDENT_2_ID/native-language") {
            contentType(ContentType.Application.Json); bearerAuth(teacherToken); setBody(SetStudentNativeLanguageRequest("uk"))
        }
        assertEquals(HttpStatusCode.Forbidden, notTheirTeacher.status)

        val byStudent = client.patchMe(studentToken, UpdateProfileRequest(nativeLanguage = "ru"))
        assertEquals("ru", byStudent.body<UserResponse>().nativeLanguage)
        assertEquals("UNSUPPORTED_NATIVE_LANGUAGE", client.patchMe(studentToken, UpdateProfileRequest(nativeLanguage = "de")).body<ProblemDetail>().code)
    }

    @Test
    fun `teachers cannot set a native language on themselves, admins can on students`() = testApp {
        val client = createJsonClient(this)

        val teacher = client.patchMe(getTeacherToken(client), UpdateProfileRequest(nativeLanguage = "uk"))
        assertEquals(HttpStatusCode.BadRequest, teacher.status)
        assertEquals("NATIVE_LANGUAGE_STUDENTS_ONLY", teacher.body<ProblemDetail>().code)

        val admin = client.patch("/api/v1/admin/users/$STUDENT_ID") {
            bearerAuth(getAdminToken(client)); contentType(ContentType.Application.Json); setBody(mapOf("nativeLanguage" to "uk"))
        }
        assertEquals("uk", admin.body<AdminUserResponse>().nativeLanguage)
    }

    @Test
    fun `the A1-A2 display default follows the native language`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        suspend fun settings() = client.get("/api/v1/students/$STUDENT_ID/vocab-settings") { bearerAuth(teacherToken) }.body<VocabSettingsResponse>()

        assertEquals(listOf("ru"), settings().fields)
        client.patchMe(getStudentToken(client), UpdateProfileRequest(nativeLanguage = "uk"))
        assertEquals(listOf("uk"), settings().fields)
        assertEquals("uk", settings().nativeLanguage)

        val b1 = client.put("/api/v1/students/$STUDENT_ID/level") {
            contentType(ContentType.Application.Json); bearerAuth(teacherToken); setBody(SetStudentLevelRequest(LanguageLevel.B1))
        }.body<VocabSettingsResponse>()
        assertEquals(listOf("de_explanation"), b1.fields)
    }
}

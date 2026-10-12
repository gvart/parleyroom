package com.gvart.parleyroom.admin

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.admin.data.FeatureFlag
import com.gvart.parleyroom.admin.service.FeatureFlagService
import com.gvart.parleyroom.common.transfer.FieldError
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureFlagIntegrationTest : IntegrationTest() {

    private val allOff = mapOf("roadmap" to false, "lessonPrep" to false, "teacherReminders" to false)

    private suspend fun HttpClient.putFlags(token: String, body: Map<String, Boolean>): HttpResponse =
        put("/api/v1/admin/feature-flags") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `flags default to off`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/feature-flags") { bearerAuth(getAdminToken(client)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(allOff, response.body<Map<String, Boolean>>())

        val me = client.get("/api/v1/users/me") { bearerAuth(getStudentToken(client)) }.body<UserResponse>()
        assertEquals(allOff, me.features)
    }

    @Test
    fun `admin toggles a flag and every client sees it`() = testApp {
        val client = createJsonClient(this)
        val admin = getAdminToken(client)

        val response = client.putFlags(admin, mapOf("roadmap" to true))
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(allOff + ("roadmap" to true), response.body<Map<String, Boolean>>())
        assertTrue(FeatureFlagService().isEnabled(FeatureFlag.ROADMAP))

        // Flags left out of the body keep their value.
        client.putFlags(admin, mapOf("lessonPrep" to true))
        val flags = client.get("/api/v1/admin/feature-flags") { bearerAuth(admin) }.body<Map<String, Boolean>>()
        assertEquals(allOff + ("roadmap" to true) + ("lessonPrep" to true), flags)

        for (token in listOf(getTeacherToken(client), getStudentToken(client))) {
            val me = client.get("/api/v1/users/me") { bearerAuth(token) }.body<UserResponse>()
            assertEquals(true, me.features?.get("roadmap"))
            assertEquals(false, me.features?.get("teacherReminders"))
        }
        val patched = client.patch("/api/v1/users/me") {
            bearerAuth(getStudentToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf("firstName" to "Flag"))
        }.body<UserResponse>()
        assertEquals(true, patched.features?.get("lessonPrep"))

        client.putFlags(admin, mapOf("roadmap" to false))
        assertFalse(FeatureFlagService().isEnabled(FeatureFlag.ROADMAP))
    }

    @Test
    fun `unknown flag is rejected with its pointer`() = testApp {
        val client = createJsonClient(this)
        val response = client.putFlags(getAdminToken(client), mapOf("roadmap" to true, "nope" to true))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val problem = response.body<ProblemDetail>()
        assertEquals("UNKNOWN_FEATURE_FLAG", problem.code)
        assertEquals(listOf(FieldError("/nope", "UNKNOWN_FEATURE_FLAG", "Unknown feature flag: nope")), problem.errors)
        assertFalse(FeatureFlagService().isEnabled(FeatureFlag.ROADMAP), "nothing is applied")
    }

    @Test
    fun `non admins cannot read or toggle flags`() = testApp {
        val client = createJsonClient(this)
        for (token in listOf(getTeacherToken(client), getStudentToken(client))) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/feature-flags") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.Forbidden, client.putFlags(token, mapOf("roadmap" to true)).status)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/feature-flags").status)
        assertFalse(FeatureFlagService().isEnabled(FeatureFlag.ROADMAP))
    }
}


package com.gvart.parleyroom.admin

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.admin.service.AppSettingsService
import com.gvart.parleyroom.admin.transfer.AppSettingsDto
import com.gvart.parleyroom.common.transfer.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsIntegrationTest : IntegrationTest() {

    @Test
    fun `admin reads the default settings`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/admin/settings") { bearerAuth(getAdminToken(client)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(AppSettingsDto(inviteExpiryDays = 7), response.body<AppSettingsDto>())
    }

    @Test
    fun `admin updates invite expiry and the service sees it`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)
        val response = client.put("/api/v1/admin/settings") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(AppSettingsDto(inviteExpiryDays = 14))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(14, response.body<AppSettingsDto>().inviteExpiryDays)
        assertEquals(14, client.get("/api/v1/admin/settings") { bearerAuth(token) }.body<AppSettingsDto>().inviteExpiryDays)
        assertEquals(14, AppSettingsService().inviteExpiryDays())
    }

    @Test
    fun `out of range invite expiry is rejected`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)
        for (days in listOf(0, 91)) {
            val response = client.put("/api/v1/admin/settings") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(AppSettingsDto(inviteExpiryDays = days))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("VALIDATION_FAILED", response.body<ProblemDetail>().code)
        }
    }

    @Test
    fun `non admins get 403`() = testApp {
        val client = createJsonClient(this)
        for (token in listOf(getTeacherToken(client), getStudentToken(client))) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/settings") { bearerAuth(token) }.status)
            val put = client.put("/api/v1/admin/settings") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(AppSettingsDto(inviteExpiryDays = 10))
            }
            assertEquals(HttpStatusCode.Forbidden, put.status)
        }
    }
}

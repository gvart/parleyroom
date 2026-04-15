package com.gvart.parleyroom.notification

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.notification.transfer.MarkViewedRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationIntegrationTest : IntegrationTest() {

    @Test
    fun `mark viewed with empty notificationIds fails validation`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        val response = client.post("/api/v1/notifications/viewed") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MarkViewedRequest(notificationIds = emptyList()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `mark viewed with non-empty notificationIds passes validation`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)

        // This will pass validation but may fail at the service level if the IDs don't exist
        // The important thing is it doesn't return 400 (validation error)
        val response = client.post("/api/v1/notifications/viewed") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MarkViewedRequest(notificationIds = listOf("00000000-0000-0000-0000-000000000099")))
        }

        // Should pass validation (200 OK) - the notification may not exist but validation passes
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
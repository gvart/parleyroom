package com.gvart.parleyroom.notification

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.notification.transfer.MarkViewedRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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

    @Test
    fun `stream with an expired token answers 401 when the client also accepts json`() = testApp {
        // Plain client: the JSON client would add Accept: application/json itself. With only
        // text/event-stream the JSON problem becomes a 406 and the portal never sees the 401.
        val response = client.get("/api/v1/notifications/stream") {
            bearerAuth("expired.or.invalid")
            header(HttpHeaders.Accept, "text/event-stream, application/json")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

package com.gvart.parleyroom.notification

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.notification.transfer.MarkViewedRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.path
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
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

    @Test
    fun `stream connects with a bearer header`() = testApp {
        captureStreamStatus()
        val token = getStudentToken(createJsonClient(this))

        val status = openStream("/api/v1/notifications/stream") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, status)
    }

    @Test
    fun `stream connects with an access_token query parameter`() = testApp {
        captureStreamStatus()
        val token = getStudentToken(createJsonClient(this))

        val status = openStream("/api/v1/notifications/stream?access_token=$token")

        assertEquals(HttpStatusCode.OK, status)
    }

    @Test
    fun `stream with an invalid query token answers 401 not 406`() = testApp {
        val response = client.get("/api/v1/notifications/stream?access_token=expired.or.invalid") {
            header(HttpHeaders.Accept, "text/event-stream")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }

    @Test
    fun `stream without a token answers 401 not 406`() = testApp {
        val response = client.get("/api/v1/notifications/stream") {
            header(HttpHeaders.Accept, "text/event-stream")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `unauthenticated call accepting only event-stream answers 401 not 406`() = testApp {
        val response = client.get("/api/v1/users/me") {
            header(HttpHeaders.Accept, "text/event-stream")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `access_token query parameter is ignored outside the stream`() = testApp {
        val token = getStudentToken(createJsonClient(this))

        val response = client.get("/api/v1/notifications?access_token=$token")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // The test engine hands the response to the client only once the call completes, and the
    // stream never does: capture the status server-side as the response starts, then hang up.
    private suspend fun ApplicationTestBuilder.openStream(
        url: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpStatusCode = coroutineScope {
        val connection = launch {
            client.get(url) {
                header(HttpHeaders.Accept, "text/event-stream")
                block()
            }
        }
        withContext(Dispatchers.IO) { withTimeout(10.seconds) { streamStatus.await() } }
            .also { connection.cancel() }
    }

    private val streamStatus = CompletableDeferred<HttpStatusCode>()

    private fun ApplicationTestBuilder.captureStreamStatus() = application {
        install(createApplicationPlugin("CaptureStreamStatus") {
            on(ResponseBodyReadyForSend) { call, _ ->
                if (call.request.path() == "/api/v1/notifications/stream") {
                    streamStatus.complete(call.response.status() ?: HttpStatusCode.OK)
                }
            }
        })
    }
}

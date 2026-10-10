package com.gvart.parleyroom.email

import com.gvart.parleyroom.email.config.MailConfig
import com.gvart.parleyroom.email.service.CloudflareEmailSender
import com.gvart.parleyroom.email.service.InlineImage
import com.gvart.parleyroom.email.service.OutgoingEmail
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudflareEmailSenderTest {

    private val config = MailConfig(
        provider = "cloudflare", cloudflareAccountId = "acc123", cloudflareApiToken = "cf-token",
        from = "noreply@sprachrobbe.com", brandName = "Sprach|robbe",
        appBaseUrl = "https://sprachrobbe.com", apiPublicUrl = "https://api.sprachrobbe.com",
    )
    private val email = OutgoingEmail(
        to = "student@test.com", subject = "Hi", html = "<p>Hi</p>", text = "Hi",
        headers = mapOf(
            "List-Unsubscribe" to "<https://api.sprachrobbe.com/api/v1/email/unsubscribe?token=abc>",
            "List-Unsubscribe-Post" to "List-Unsubscribe=One-Click",
        ),
        inlineImages = listOf(InlineImage("robbi", "robbi-idle.png", "image/png", byteArrayOf(1, 2, 3))),
    )

    private fun send(status: HttpStatusCode, body: String, captured: MutableList<HttpRequestData> = mutableListOf()): Boolean = runBlocking {
        val engine = MockEngine { request ->
            captured += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        CloudflareEmailSender(config, HttpClient(engine)).send(email)
    }

    @Test
    fun `posts the documented payload to the account's send endpoint`() {
        val requests = mutableListOf<HttpRequestData>()
        val ok = send(HttpStatusCode.OK, SUCCESS, requests)
        assertTrue(ok)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.cloudflare.com/client/v4/accounts/acc123/email/sending/send", request.url.toString())
        assertEquals("Bearer cf-token", request.headers[HttpHeaders.Authorization])

        val payload = runBlocking { Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject }
        assertEquals(Json.parseToJsonElement("""{"address":"noreply@sprachrobbe.com","name":"Sprachrobbe"}"""), payload["from"])
        assertEquals("student@test.com", payload["to"]!!.jsonPrimitive.content)
        assertEquals("Hi", payload["subject"]!!.jsonPrimitive.content)
        assertEquals("<p>Hi</p>", payload["html"]!!.jsonPrimitive.content)
        assertEquals("Hi", payload["text"]!!.jsonPrimitive.content)
        val headers = payload["headers"]!!.jsonObject
        assertEquals("<https://api.sprachrobbe.com/api/v1/email/unsubscribe?token=abc>", headers["List-Unsubscribe"]!!.jsonPrimitive.content)
        assertEquals("List-Unsubscribe=One-Click", headers["List-Unsubscribe-Post"]!!.jsonPrimitive.content)
        val attachment = payload["attachments"]!!.jsonArray.single().jsonObject
        assertEquals(
            Json.parseToJsonElement(
                """{"content":"${Base64.encode(byteArrayOf(1, 2, 3))}","content_id":"robbi","disposition":"inline","filename":"robbi-idle.png","type":"image/png"}""",
            ),
            attachment,
        )
    }

    @Test
    fun `no headers or attachments are sent when there are none`() {
        val requests = mutableListOf<HttpRequestData>()
        runBlocking {
            val engine = MockEngine { request ->
                requests += request
                respond(SUCCESS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            CloudflareEmailSender(config, HttpClient(engine)).send(email.copy(headers = emptyMap(), inlineImages = emptyList()))
        }
        val payload = runBlocking { Json.parseToJsonElement(requests.single().body.toByteArray().decodeToString()) as JsonObject }
        assertFalse("headers" in payload)
        assertFalse("attachments" in payload)
    }

    @Test
    fun `api errors, bounces and network failures report false`() {
        assertFalse(send(HttpStatusCode.BadRequest, """{"success":false,"errors":[{"code":10001,"message":"email.sending.error.invalid_request_schema"}],"messages":[],"result":null}"""))
        assertFalse(send(HttpStatusCode.OK, """{"success":true,"errors":[],"messages":[],"result":{"delivered":[],"permanent_bounces":["student@test.com"],"queued":[]}}"""))
        assertFalse(send(HttpStatusCode.OK, "not json"))
        val failing = runBlocking {
            CloudflareEmailSender(config, HttpClient(MockEngine { error("connection reset") })).send(email)
        }
        assertFalse(failing)
    }

    private companion object {
        const val SUCCESS = """{"success":true,"errors":[],"messages":[],"result":{"delivered":["student@test.com"],"permanent_bounces":[],"queued":[]}}"""
    }
}

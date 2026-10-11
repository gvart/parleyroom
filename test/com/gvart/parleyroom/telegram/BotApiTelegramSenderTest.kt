package com.gvart.parleyroom.telegram

import com.gvart.parleyroom.telegram.service.BotApiTelegramSender
import com.gvart.parleyroom.telegram.service.OutgoingTelegramMessage
import com.gvart.parleyroom.telegram.service.TelegramButton
import com.gvart.parleyroom.telegram.service.TelegramSendResult
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
import kotlin.test.Test
import kotlin.test.assertEquals

class BotApiTelegramSenderTest {

    private val message = OutgoingTelegramMessage(
        chatId = 42L, text = "<b>Perfekt</b> starts in 1 hour",
        button = TelegramButton("View lesson", "https://miniapp.example.com/lessons", webApp = true),
    )

    private fun send(
        status: HttpStatusCode,
        body: String = OK,
        msg: OutgoingTelegramMessage = message,
        captured: MutableList<HttpRequestData> = mutableListOf(),
    ): TelegramSendResult = runBlocking {
        val engine = MockEngine { request ->
            captured += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        BotApiTelegramSender("123:abc", HttpClient(engine)).send(msg)
    }

    private fun payload(request: HttpRequestData) = runBlocking { Json.parseToJsonElement(request.body.toByteArray().decodeToString()) }

    @Test
    fun `posts sendMessage with HTML parse mode and a web_app button`() {
        val requests = mutableListOf<HttpRequestData>()
        assertEquals(TelegramSendResult.SENT, send(HttpStatusCode.OK, captured = requests))
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.telegram.org/bot123:abc/sendMessage", request.url.toString())
        assertEquals(
            Json.parseToJsonElement(
                """{"chat_id":42,"text":"<b>Perfekt</b> starts in 1 hour","parse_mode":"HTML",
                   "link_preview_options":{"is_disabled":true},
                   "reply_markup":{"inline_keyboard":[[{"text":"View lesson","web_app":{"url":"https://miniapp.example.com/lessons"}}]]}}""",
            ),
            payload(request),
        )
    }

    @Test
    fun `a portal fallback is a plain url button`() {
        val requests = mutableListOf<HttpRequestData>()
        send(HttpStatusCode.OK, msg = message.copy(button = TelegramButton("Open", "https://app.example.com/student/lessons", webApp = false)), captured = requests)
        assertEquals(
            Json.parseToJsonElement("""{"inline_keyboard":[[{"text":"Open","url":"https://app.example.com/student/lessons"}]]}"""),
            (payload(requests.single()) as kotlinx.serialization.json.JsonObject)["reply_markup"],
        )
    }

    @Test
    fun `403 is forbidden, other errors and network failures are failures`() {
        assertEquals(
            TelegramSendResult.FORBIDDEN,
            send(HttpStatusCode.Forbidden, """{"ok":false,"error_code":403,"description":"Forbidden: bot was blocked by the user"}"""),
        )
        assertEquals(
            TelegramSendResult.FAILED,
            send(HttpStatusCode.BadRequest, """{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}"""),
        )
        assertEquals(TelegramSendResult.FAILED, send(HttpStatusCode.TooManyRequests, """{"ok":false,"error_code":429}"""))
        val failing = runBlocking {
            BotApiTelegramSender("123:abc", HttpClient(MockEngine { error("connection reset") })).send(message)
        }
        assertEquals(TelegramSendResult.FAILED, failing)
    }

    private companion object {
        const val OK = """{"ok":true,"result":{"message_id":1}}"""
    }
}

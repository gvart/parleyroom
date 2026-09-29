package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.llm.KoogAnthropicGateway
import com.gvart.parleyroom.ai.llm.LlmMessage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Asserts the exact JSON Koog sends to Anthropic, captured by a local stub of /v1/messages. */
class KoogAnthropicGatewayTest {

    private var stopReason = "end_turn"
    private val requests = mutableListOf<JsonObject>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { exchange ->
            requests += Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val body = """
                {"id":"msg_1","type":"message","role":"assistant","model":"m",
                 "content":[{"type":"text","text":"Hallo"}],
                 "stop_reason":"$stopReason","usage":{"input_tokens":3,"output_tokens":1}}
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun send(modelId: String): JsonObject = runBlocking {
        val gateway = KoogAnthropicGateway("test-key", modelId, 10.seconds, "http://127.0.0.1:${server.address.port}")
        val reply = gateway.complete("system", listOf(LlmMessage.user("hi")), 1000)
        assertEquals("Hallo", reply.text)
        requests.single()
    }

    private fun assertNoSamplingParams(request: JsonObject) {
        listOf("temperature", "top_p", "top_k", "tool_choice").forEach {
            assertFalse(it in request, "unexpected '$it' in $request")
        }
        // Koog always sends an empty tool list; without tool_choice that is a plain completion.
        assertEquals(JsonArray(emptyList()), request["tools"] ?: JsonArray(emptyList()))
    }

    @Test
    fun `sonnet 5_5 sends thinking between_tools and no sampling params`() {
        val request = send("claude-sonnet-5-5")
        assertEquals("claude-sonnet-5-5", request["model"]!!.jsonPrimitive.content)
        assertEquals(1000, request["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(Json.parseToJsonElement("""{"type":"between_tools"}"""), request["thinking"])
        assertNoSamplingParams(request)
    }

    @Test
    fun `sonnet 5 keeps thinking disabled and no sampling params`() {
        val request = send("claude-sonnet-5")
        assertEquals("claude-sonnet-5", request["model"]!!.jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"type":"disabled"}"""), request["thinking"])
        assertNoSamplingParams(request)
    }

    @Test
    fun `max_tokens stop reason marks the reply truncated`() = runBlocking {
        stopReason = "max_tokens"
        val gateway = KoogAnthropicGateway("test-key", "claude-sonnet-5-5", 10.seconds, "http://127.0.0.1:${server.address.port}")
        assertTrue(gateway.complete("system", listOf(LlmMessage.user("hi")), 1000).truncated)
    }
}

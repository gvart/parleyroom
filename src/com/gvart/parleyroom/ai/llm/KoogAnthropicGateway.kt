package com.gvart.parleyroom.ai.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Anthropic through Koog's [AnthropicLLMClient]. The model is chosen per call; an id Koog does not
 * know yet is registered on first use with Sonnet 5's capabilities so new models need no upgrade.
 */
class KoogAnthropicGateway(
    apiKey: String,
    requestTimeout: Duration,
    baseUrl: String = "https://api.anthropic.com",
) : LlmGateway {

    private val log = LoggerFactory.getLogger(KoogAnthropicGateway::class.java)

    // Koog reads this map on every request, so models registered later are found too.
    private val modelVersions = ConcurrentHashMap(AnthropicClientSettings().modelVersionsMap)

    private val client = AnthropicLLMClient(
        apiKey = apiKey,
        settings = AnthropicClientSettings(
            modelVersionsMap = modelVersions,
            baseUrl = baseUrl,
            timeoutConfig = ConnectionTimeoutConfig(requestTimeoutMillis = requestTimeout.inWholeMilliseconds),
        ),
    )

    private fun llModel(modelId: String): LLModel = modelVersions.entries.firstOrNull { it.value == modelId }?.key
        ?: LLModel(
            provider = LLMProvider.Anthropic,
            id = modelId,
            capabilities = AnthropicModels.Sonnet_5.capabilities,
            contextLength = AnthropicModels.Sonnet_5.contextLength,
            maxOutputTokens = AnthropicModels.Sonnet_5.maxOutputTokens,
        ).also { modelVersions[it] = modelId }

    override suspend fun complete(model: String, system: String, messages: List<LlmMessage>, maxTokens: Int): LlmReply {
        // Sonnet 5+ thinks by default and thinking counts against maxTokens: a Nachbereitung spent all
        // 16k tokens thinking and returned no text. Callers size maxTokens for the JSON answer alone.
        // Sonnet 5.5 rejects "disabled" (400); "between_tools" is its equivalent without tools. Koog 1.3
        // has no such AnthropicThinking variant, so it goes in as an extra top-level body field.
        val params = if (usesBetweenTools(model)) {
            AnthropicParams(maxTokens = maxTokens, additionalProperties = mapOf("thinking" to BETWEEN_TOOLS))
        } else {
            AnthropicParams(maxTokens = maxTokens, thinking = AnthropicThinking.Disabled())
        }
        val request = prompt("parleyroom-ai", params = params) {
            // Ephemeral (5 min) cache on the system prompt; prompts under the model's minimum just don't cache.
            system(system, AnthropicCacheControl.Default)
            messages.forEach {
                when (it.role) {
                    LlmMessage.Role.USER -> user(it.text)
                    LlmMessage.Role.ASSISTANT -> assistant(it.text)
                }
            }
        }
        val response = try {
            client.execute(request, llModel(model), emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Koog puts the HTTP status into the (nested) exception messages: "Status code: 429".
            val messages = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            log.warn("Anthropic request failed: {}", messages.take(1000))
            val rateLimited = "Status code: 429" in messages || "rate_limit_error" in messages
            throw LlmException(if (rateLimited) "AI_RATE_LIMITED" else "AI_PROVIDER_ERROR", "The AI provider request failed", e)
        }
        return LlmReply(
            text = response.textContent(),
            inputTokens = response.metaInfo.inputTokensCount,
            outputTokens = response.metaInfo.outputTokensCount,
            truncated = response.finishReason == "max_tokens",
            cacheReadTokens = response.metaInfo.metadata?.intOrZero("cacheReadInputTokens") ?: 0,
            cacheWriteTokens = response.metaInfo.metadata?.intOrZero("cacheCreationInputTokens") ?: 0,
        )
    }

    companion object {
        private val BETWEEN_TOOLS: JsonElement = buildJsonObject { put("type", JsonPrimitive("between_tools")) }

        fun usesBetweenTools(modelId: String) = modelId.startsWith("claude-sonnet-5-5")

        private fun JsonObject.intOrZero(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
    }
}

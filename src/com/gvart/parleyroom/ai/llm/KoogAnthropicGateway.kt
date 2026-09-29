package com.gvart.parleyroom.ai.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Anthropic through Koog's [AnthropicLLMClient]. The model id comes from config; an id Koog
 * does not know yet is registered with Sonnet 5's capabilities so new models need no upgrade.
 */
class KoogAnthropicGateway(
    apiKey: String,
    override val modelId: String,
    requestTimeout: Duration,
    baseUrl: String = "https://api.anthropic.com",
) : LlmGateway {

    private val log = LoggerFactory.getLogger(KoogAnthropicGateway::class.java)

    private val knownModels = AnthropicClientSettings().modelVersionsMap

    private val model: LLModel = knownModels.entries.firstOrNull { it.value == modelId }?.key
        ?: LLModel(
            provider = LLMProvider.Anthropic,
            id = modelId,
            capabilities = AnthropicModels.Sonnet_5.capabilities,
            contextLength = AnthropicModels.Sonnet_5.contextLength,
            maxOutputTokens = AnthropicModels.Sonnet_5.maxOutputTokens,
        )

    private val client = AnthropicLLMClient(
        apiKey = apiKey,
        settings = AnthropicClientSettings(
            modelVersionsMap = knownModels + (model to modelId),
            baseUrl = baseUrl,
            timeoutConfig = ConnectionTimeoutConfig(requestTimeoutMillis = requestTimeout.inWholeMilliseconds),
        ),
    )

    override suspend fun complete(system: String, messages: List<LlmMessage>, maxTokens: Int): LlmReply {
        // Sonnet 5+ thinks by default and thinking counts against maxTokens: a Nachbereitung spent all
        // 16k tokens thinking and returned no text. Callers size maxTokens for the JSON answer alone.
        // Sonnet 5.5 rejects "disabled" (400); "between_tools" is its equivalent without tools. Koog 1.3
        // has no such AnthropicThinking variant, so it goes in as an extra top-level body field.
        val params = if (usesBetweenTools(modelId)) {
            AnthropicParams(maxTokens = maxTokens, additionalProperties = mapOf("thinking" to BETWEEN_TOOLS))
        } else {
            AnthropicParams(maxTokens = maxTokens, thinking = AnthropicThinking.Disabled())
        }
        val request = prompt("parleyroom-ai", params = params) {
            system(system)
            messages.forEach {
                when (it.role) {
                    LlmMessage.Role.USER -> user(it.text)
                    LlmMessage.Role.ASSISTANT -> assistant(it.text)
                }
            }
        }
        val response = try {
            client.execute(request, model, emptyList())
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
        )
    }

    companion object {
        private val BETWEEN_TOOLS: JsonElement = buildJsonObject { put("type", JsonPrimitive("between_tools")) }

        fun usesBetweenTools(modelId: String) = modelId.startsWith("claude-sonnet-5-5")
    }
}

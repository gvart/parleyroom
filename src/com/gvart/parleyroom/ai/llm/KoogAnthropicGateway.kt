package com.gvart.parleyroom.ai.llm

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * Anthropic through Koog's [AnthropicLLMClient]. The model id comes from config; an id Koog
 * does not know yet is registered with Sonnet 5's capabilities so new models need no upgrade.
 */
class KoogAnthropicGateway(apiKey: String, override val modelId: String, requestTimeout: Duration) : LlmGateway {

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
            timeoutConfig = ConnectionTimeoutConfig(requestTimeoutMillis = requestTimeout.inWholeMilliseconds),
        ),
    )

    override suspend fun complete(system: String, messages: List<LlmMessage>, maxTokens: Int): LlmReply {
        val request = prompt("parleyroom-ai", params = AnthropicParams(maxTokens = maxTokens)) {
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
            log.warn("Anthropic request failed: {}", e.message)
            val rateLimited = e.message.orEmpty().let { it.contains("429") || it.contains("rate_limit", ignoreCase = true) }
            throw LlmException(if (rateLimited) "AI_RATE_LIMITED" else "AI_PROVIDER_ERROR", "The AI provider request failed", e)
        }
        return LlmReply(
            text = response.textContent(),
            inputTokens = response.metaInfo.inputTokensCount,
            outputTokens = response.metaInfo.outputTokensCount,
        )
    }
}

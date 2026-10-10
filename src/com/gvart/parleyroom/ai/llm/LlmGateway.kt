package com.gvart.parleyroom.ai.llm

/** One turn of a conversation sent to the model after the system prompt. */
data class LlmMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }

    companion object {
        fun user(text: String) = LlmMessage(Role.USER, text)
        fun assistant(text: String) = LlmMessage(Role.ASSISTANT, text)
    }
}

/**
 * [truncated]: the model hit `maxTokens`, so [text] is cut off (or empty when the budget went to thinking).
 * [inputTokens] excludes the cached prompt tokens ([cacheReadTokens], [cacheWriteTokens]).
 */
data class LlmReply(
    val text: String,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val truncated: Boolean = false,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/** A provider failure; [code] is the job error code (AI_PROVIDER_ERROR, AI_RATE_LIMITED). */
class LlmException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Provider-agnostic text completion with the model chosen per call. Implementations: [KoogAnthropicGateway], [FakeLlmGateway]. */
interface LlmGateway {
    suspend fun complete(model: String, system: String, messages: List<LlmMessage>, maxTokens: Int): LlmReply
}

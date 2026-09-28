package com.gvart.parleyroom.ai.llm

/** One turn of a conversation sent to the model after the system prompt. */
data class LlmMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }

    companion object {
        fun user(text: String) = LlmMessage(Role.USER, text)
        fun assistant(text: String) = LlmMessage(Role.ASSISTANT, text)
    }
}

data class LlmReply(val text: String, val inputTokens: Int?, val outputTokens: Int?)

/** A provider failure; [code] is the job error code (AI_PROVIDER_ERROR, AI_RATE_LIMITED). */
class LlmException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Provider-agnostic text completion. Implementations: [KoogAnthropicGateway], [FakeLlmGateway]. */
interface LlmGateway {
    val modelId: String

    suspend fun complete(system: String, messages: List<LlmMessage>, maxTokens: Int): LlmReply
}

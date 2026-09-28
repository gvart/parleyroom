package com.gvart.parleyroom.ai.config

import com.gvart.parleyroom.ai.llm.LlmGateway
import kotlin.time.Duration

data class AiConfig(
    /** `anthropic` or `fake`. */
    val provider: String,
    val model: String,
    val anthropicApiKey: String,
    val maxActiveJobsPerTeacher: Int,
    val maxConcurrentJobs: Int,
    val jobTimeout: Duration,
) {
    /** False when the provider needs a key and none is configured: job starts then return 503. */
    val enabled: Boolean get() = provider == PROVIDER_FAKE || anthropicApiKey.isNotBlank()

    companion object {
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_FAKE = "fake"
    }
}

/** The configured provider; [gateway] is null when AI is not configured (job starts return 503). */
class AiRuntime(val config: AiConfig, val gateway: LlmGateway?)

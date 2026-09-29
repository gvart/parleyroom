package com.gvart.parleyroom.ai.config

import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.llm.KoogAnthropicGateway
import com.gvart.parleyroom.ai.llm.LlmGateway
import com.gvart.parleyroom.ai.routing.configureAiRouting
import com.gvart.parleyroom.ai.service.FillTranslationsService
import com.gvart.parleyroom.ai.service.GenerationJobRunner
import com.gvart.parleyroom.ai.service.LessonContextService
import com.gvart.parleyroom.ai.service.LibrarySuggestionService
import com.gvart.parleyroom.ai.service.NachbereitungPublishService
import com.gvart.parleyroom.ai.service.NachbereitungService
import com.gvart.parleyroom.ai.service.PromptTemplateService
import com.gvart.parleyroom.ai.service.SuggestTagsService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.di.dependencies
import kotlin.time.Duration

fun Application.configureAiModule() {
    val config = environment.config
    fun value(path: String, default: String) = config.propertyOrNull(path)?.getString() ?: default

    val aiConfig = AiConfig(
        provider = value("ai.provider", AiConfig.PROVIDER_ANTHROPIC).lowercase(),
        model = value("ai.model", "claude-sonnet-5-5"),
        anthropicApiKey = value("ai.anthropic_api_key", ""),
        maxActiveJobsPerTeacher = value("ai.max_active_jobs_per_teacher", "2").toInt(),
        maxConcurrentJobs = value("ai.max_concurrent_jobs", "3").toInt(),
        jobTimeout = Duration.parse(value("ai.job_timeout", "600s")),
    )
    val gateway: LlmGateway? = when {
        aiConfig.provider == AiConfig.PROVIDER_FAKE -> FakeLlmGateway()
        aiConfig.provider == AiConfig.PROVIDER_ANTHROPIC && aiConfig.enabled ->
            KoogAnthropicGateway(aiConfig.anthropicApiKey, aiConfig.model, requestTimeout = aiConfig.jobTimeout / 2)
        else -> null
    }
    if (gateway == null) environment.log.warn("AI provider '${aiConfig.provider}' is not configured (ANTHROPIC_API_KEY empty?): AI endpoints return 503")
    else environment.log.info("AI provider: ${aiConfig.provider}, model ${gateway.modelId}")

    val runner = GenerationJobRunner(aiConfig)
    val interrupted = runner.failInterruptedJobs()
    if (interrupted > 0) environment.log.warn("Marked $interrupted interrupted AI jobs as FAILED")
    monitor.subscribe(ApplicationStopping) { runner.shutdown() }

    dependencies {
        provide { AiRuntime(aiConfig, gateway) }
        provide { runner }
        provide(LessonContextService::class)
        provide(NachbereitungService::class)
        provide(NachbereitungPublishService::class)
        provide(PromptTemplateService::class)
        provide(LibrarySuggestionService::class)
        provide(FillTranslationsService::class)
        provide(SuggestTagsService::class)
    }

    configureAiRouting()
}

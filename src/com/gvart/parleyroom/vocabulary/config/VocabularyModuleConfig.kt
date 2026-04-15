package com.gvart.parleyroom.vocabulary.config

import com.gvart.parleyroom.vocabulary.routing.configureVocabularyRouting
import com.gvart.parleyroom.vocabulary.service.VocabularyReviewReminderService
import com.gvart.parleyroom.vocabulary.service.VocabularyService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

fun Application.configureVocabularyModule() {
    dependencies {
        provide(VocabularyService::class)
        provide(VocabularyReviewReminderService::class)
    }

    val config = environment.config
    val enabled = config.propertyOrNull("vocabulary.review_reminder.enabled")?.getString()?.toBoolean() ?: true
    val interval = Duration.parse(
        config.propertyOrNull("vocabulary.review_reminder.interval")?.getString() ?: "1h"
    )

    if (enabled) {
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + supervisor)
        val reminderService: VocabularyReviewReminderService by dependencies

        val log = environment.log
        monitor.subscribe(ApplicationStarted) {
            scope.launch {
                while (isActive) {
                    delay(interval)
                    runCatching { reminderService.sendReminders() }
                        .onFailure { log.warn("Vocabulary reminder job failed", it) }
                }
            }
        }
        monitor.subscribe(ApplicationStopping) {
            supervisor.cancel()
        }
    }

    configureVocabularyRouting()
}

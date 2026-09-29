package com.gvart.parleyroom.lesson.config

import com.gvart.parleyroom.lesson.routing.configureLessonRouting
import com.gvart.parleyroom.lesson.service.LessonContentService
import com.gvart.parleyroom.lesson.service.LessonLifecycleService
import com.gvart.parleyroom.lesson.service.LessonParticipantService
import com.gvart.parleyroom.lesson.service.LessonRescheduleService
import com.gvart.parleyroom.lesson.service.LessonService
import com.gvart.parleyroom.lesson.service.LessonSupport
import com.gvart.parleyroom.availability.service.AvailabilityService
import com.gvart.parleyroom.availability.service.AvailabilityValidator
import com.gvart.parleyroom.availability.service.SlotComputationService
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
import kotlin.time.toJavaDuration

fun Application.configureLessonModule() {
    dependencies {
        provide(LessonSupport::class)
        provide(LessonService::class)
        provide(LessonLifecycleService::class)
        provide(LessonParticipantService::class)
        provide(LessonRescheduleService::class)
        provide(LessonContentService::class)
    }

    val config = environment.config
    val enabled = config.propertyOrNull("lesson.auto_complete.enabled")?.getString()?.toBoolean() ?: true
    val interval = Duration.parse(
        config.propertyOrNull("lesson.auto_complete.interval")?.getString() ?: "5m"
    )
    val grace = Duration.parse(
        config.propertyOrNull("lesson.auto_complete.grace")?.getString() ?: "60m"
    )

    if (enabled) {
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + supervisor)
        val lifecycleService: LessonLifecycleService by dependencies

        val log = environment.log
        monitor.subscribe(ApplicationStarted) {
            scope.launch {
                while (isActive) {
                    delay(interval)
                    runCatching { lifecycleService.autoCompleteStaleLessons(grace.toJavaDuration()) }
                        .onFailure { log.warn("Lesson auto-complete job failed", it) }
                }
            }
        }
        monitor.subscribe(ApplicationStopping) {
            supervisor.cancel()
        }
    }

    configureLessonRouting()
}

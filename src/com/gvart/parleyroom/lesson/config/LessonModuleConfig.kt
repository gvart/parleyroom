package com.gvart.parleyroom.lesson.config

import com.gvart.parleyroom.lesson.routing.configureLessonRouting
import com.gvart.parleyroom.lesson.service.LessonDocumentService
import com.gvart.parleyroom.lesson.service.LessonLifecycleService
import com.gvart.parleyroom.lesson.service.LessonParticipantService
import com.gvart.parleyroom.lesson.service.LessonRescheduleService
import com.gvart.parleyroom.lesson.service.LessonService
import com.gvart.parleyroom.lesson.service.LessonSupport
import com.gvart.parleyroom.availability.service.AvailabilityService
import com.gvart.parleyroom.availability.service.AvailabilityValidator
import com.gvart.parleyroom.availability.service.SlotComputationService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureLessonModule() {
    dependencies {
        provide(LessonSupport::class)
        provide(LessonService::class)
        provide(LessonLifecycleService::class)
        provide(LessonParticipantService::class)
        provide(LessonRescheduleService::class)
        provide(LessonDocumentService::class)
    }

    configureLessonRouting()
}

package com.gvart.parleyroom.lesson.config

import com.gvart.parleyroom.lesson.routing.configureLessonRouting
import com.gvart.parleyroom.lesson.service.LessonService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureLessonModule() {
    dependencies {
        provide(LessonService::class)
    }

    configureLessonRouting()
}
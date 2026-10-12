package com.gvart.parleyroom.lessonprep.config

import com.gvart.parleyroom.lessonprep.routing.configureLessonPrepRouting
import com.gvart.parleyroom.lessonprep.service.LessonHistoryService
import com.gvart.parleyroom.lessonprep.service.LessonPrepService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureLessonPrepModule() {
    dependencies {
        provide(LessonPrepService::class)
        provide(LessonHistoryService::class)
    }

    configureLessonPrepRouting()
}

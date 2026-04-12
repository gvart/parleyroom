package com.gvart.parleyroom.homework.config

import com.gvart.parleyroom.homework.routing.configureHomeworkRouting
import com.gvart.parleyroom.homework.service.HomeworkService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureHomeworkModule() {
    dependencies {
        provide(HomeworkService::class)
    }

    configureHomeworkRouting()
}

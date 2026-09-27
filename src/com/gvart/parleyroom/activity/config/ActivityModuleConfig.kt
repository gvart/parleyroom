package com.gvart.parleyroom.activity.config

import com.gvart.parleyroom.activity.routing.configureActivityRouting
import com.gvart.parleyroom.activity.service.StreakService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureActivityModule() {
    dependencies {
        provide(StreakService::class)
    }

    configureActivityRouting()
}

package com.gvart.parleyroom.user.config

import com.gvart.parleyroom.user.routing.configureRouting
import com.gvart.parleyroom.user.service.AuthenticationService
import com.gvart.parleyroom.user.service.UserService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureUserModule() {
    dependencies {
        provide(AuthenticationService::class)
        provide(UserService::class)
    }

    configureRouting()
}
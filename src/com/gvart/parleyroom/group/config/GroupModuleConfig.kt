package com.gvart.parleyroom.group.config

import com.gvart.parleyroom.group.routing.configureGroupRouting
import com.gvart.parleyroom.group.service.GroupService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureGroupModule() {
    dependencies {
        provide(GroupService::class)
    }

    configureGroupRouting()
}

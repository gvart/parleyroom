package com.gvart.parleyroom.admin.config

import com.gvart.parleyroom.admin.routing.configureAdminRouting
import com.gvart.parleyroom.admin.service.AdminService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureAdminModule() {
    dependencies {
        provide(AdminService::class)
    }

    configureAdminRouting()
}

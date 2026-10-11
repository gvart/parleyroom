package com.gvart.parleyroom.admin.config

import com.gvart.parleyroom.admin.routing.configureAdminRouting
import com.gvart.parleyroom.admin.service.AdminService
import com.gvart.parleyroom.admin.service.AdminStatsService
import com.gvart.parleyroom.admin.service.AppSettingsService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureAdminModule() {
    dependencies {
        provide(AdminService::class)
        provide(AdminStatsService::class)
        provide(AppSettingsService::class)
    }

    configureAdminRouting()
}

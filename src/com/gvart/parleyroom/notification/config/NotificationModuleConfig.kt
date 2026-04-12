package com.gvart.parleyroom.notification.config

import com.gvart.parleyroom.notification.routing.configureNotificationRouting
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.notification.service.NotificationSseManager
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureNotificationModule() {
    dependencies {
        provide { NotificationSseManager() }
        provide(NotificationService::class)
    }

    configureNotificationRouting()
}

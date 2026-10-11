package com.gvart.parleyroom.reminder.config

import com.gvart.parleyroom.reminder.service.ReminderService
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.di.dependencies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/** After the email and Telegram modules (NotificationMailer, TelegramNotifier). */
fun Application.configureReminderModule() {
    dependencies {
        provide(ReminderService::class)
    }

    val config = environment.config
    val enabled = config.propertyOrNull("reminders.enabled")?.getString()?.toBoolean() ?: true
    val interval = Duration.parse(config.propertyOrNull("reminders.interval")?.getString() ?: "5m")

    if (enabled) {
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + supervisor)
        val reminderService: ReminderService by dependencies

        val log = environment.log
        monitor.subscribe(ApplicationStarted) {
            scope.launch {
                while (isActive) {
                    delay(interval)
                    runCatching { reminderService.runOnce() }
                        .onFailure { log.warn("Reminder job failed", it) }
                }
            }
        }
        monitor.subscribe(ApplicationStopping) {
            supervisor.cancel()
        }
    }
}

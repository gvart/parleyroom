package com.gvart.parleyroom.config

import com.gvart.parleyroom.common.service.IdempotencyService
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

/** Idempotency-Key storage, and the job deleting expired keys (always on: keys pile up otherwise). */
fun Application.configureIdempotency() {
    dependencies {
        provide(IdempotencyService::class)
    }

    val interval = Duration.parse(environment.config.propertyOrNull("idempotency.cleanup_interval")?.getString() ?: "1h")
    val supervisor = SupervisorJob()
    val scope = CoroutineScope(Dispatchers.IO + supervisor)
    val idempotency: IdempotencyService by dependencies
    val log = environment.log
    monitor.subscribe(ApplicationStarted) {
        scope.launch {
            while (isActive) {
                delay(interval)
                runCatching { idempotency.deleteExpired() }
                    .onFailure { log.warn("Idempotency key cleanup failed", it) }
            }
        }
    }
    monitor.subscribe(ApplicationStopping) {
        supervisor.cancel()
    }
}

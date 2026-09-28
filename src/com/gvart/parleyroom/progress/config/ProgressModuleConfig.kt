package com.gvart.parleyroom.progress.config

import com.gvart.parleyroom.progress.routing.configureProgressRouting
import com.gvart.parleyroom.progress.service.ProgressCalculator
import com.gvart.parleyroom.progress.service.ProgressConfig
import com.gvart.parleyroom.progress.service.ProgressService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureProgressModule() {
    val config = environment.config
    fun value(path: String, default: String) = config.propertyOrNull(path)?.getString() ?: default

    val progressConfig = ProgressConfig(
        needsWorkBelow = value("progress.needs_work_below", "0.6").toDouble(),
        minScoredItems = value("progress.min_scored_items", "3").toInt(),
        needsWorkWindow = value("progress.needs_work_window", "10").toInt(),
    )

    dependencies {
        provide { ProgressCalculator(progressConfig) }
        provide(ProgressService::class)
    }

    configureProgressRouting()
}

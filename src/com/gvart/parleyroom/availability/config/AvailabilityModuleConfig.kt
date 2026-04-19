package com.gvart.parleyroom.availability.config

import com.gvart.parleyroom.availability.routing.configureAvailabilityRouting
import com.gvart.parleyroom.availability.service.AvailabilityService
import com.gvart.parleyroom.availability.service.AvailabilityValidator
import com.gvart.parleyroom.availability.service.SlotComputationService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureAvailabilityModule() {
    dependencies {
        provide(AvailabilityService::class)
        provide(SlotComputationService::class)
        provide(AvailabilityValidator::class)
    }

    configureAvailabilityRouting()
}

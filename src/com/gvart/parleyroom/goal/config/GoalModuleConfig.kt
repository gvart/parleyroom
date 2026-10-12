package com.gvart.parleyroom.goal.config

import com.gvart.parleyroom.goal.routing.configureGoalRouting
import com.gvart.parleyroom.goal.service.GoalService
import com.gvart.parleyroom.goal.service.RoadmapGenerationService
import com.gvart.parleyroom.goal.service.RoadmapService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureGoalModule() {
    dependencies {
        provide(GoalService::class)
        provide(RoadmapService::class)
        provide(RoadmapGenerationService::class)
    }

    configureGoalRouting()
}

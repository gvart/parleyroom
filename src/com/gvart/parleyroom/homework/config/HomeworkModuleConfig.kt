package com.gvart.parleyroom.homework.config

import com.gvart.parleyroom.homework.routing.configureHomeworkRouting
import com.gvart.parleyroom.homework.service.AssignmentService
import com.gvart.parleyroom.homework.service.HomeworkService
import com.gvart.parleyroom.homework.service.HomeworkUploadService
import com.gvart.parleyroom.homework.service.HomeworkViews
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureHomeworkModule() {
    dependencies {
        provide(HomeworkViews::class)
        provide(AssignmentService::class)
        provide(HomeworkService::class)
        provide(HomeworkUploadService::class)
    }

    configureHomeworkRouting()
}

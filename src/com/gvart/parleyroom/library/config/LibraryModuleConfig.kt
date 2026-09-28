package com.gvart.parleyroom.library.config

import com.gvart.parleyroom.library.routing.configureLibraryRouting
import com.gvart.parleyroom.library.service.LibraryService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureLibraryModule() {
    dependencies {
        provide(LibraryService::class)
    }

    configureLibraryRouting()
}

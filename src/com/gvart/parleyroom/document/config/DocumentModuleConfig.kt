package com.gvart.parleyroom.document.config

import com.gvart.parleyroom.document.routing.configureDocumentRouting
import com.gvart.parleyroom.document.service.DocumentService
import com.gvart.parleyroom.document.service.DocumentSupport
import com.gvart.parleyroom.document.service.DocumentVersionService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureDocumentModule() {
    dependencies {
        provide(DocumentSupport::class)
        provide(DocumentVersionService::class)
        provide(DocumentService::class)
    }

    configureDocumentRouting()
}

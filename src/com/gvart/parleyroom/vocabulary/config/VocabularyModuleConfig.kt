package com.gvart.parleyroom.vocabulary.config

import com.gvart.parleyroom.vocabulary.routing.configureVocabularyRouting
import com.gvart.parleyroom.vocabulary.service.VocabularyService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureVocabularyModule() {
    dependencies {
        provide(VocabularyService::class)
    }

    configureVocabularyRouting()
}

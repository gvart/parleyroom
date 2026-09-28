package com.gvart.parleyroom.topic.config

import com.gvart.parleyroom.topic.routing.configureTopicRouting
import com.gvart.parleyroom.topic.service.GrammarTopicService
import com.gvart.parleyroom.topic.service.TopicService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureTopicModule() {
    dependencies {
        provide(TopicService::class)
        provide(GrammarTopicService::class)
    }

    configureTopicRouting()
}

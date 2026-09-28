package com.gvart.parleyroom

import com.gvart.parleyroom.activity.config.configureActivityModule
import com.gvart.parleyroom.ai.config.configureAiModule
import com.gvart.parleyroom.admin.config.configureAdminModule
import com.gvart.parleyroom.availability.config.configureAvailabilityModule
import com.gvart.parleyroom.config.configureDatabase
import com.gvart.parleyroom.document.config.configureDocumentModule
import com.gvart.parleyroom.config.configureHealthRouting
import com.gvart.parleyroom.config.generalConfig
import com.gvart.parleyroom.goal.config.configureGoalModule
import com.gvart.parleyroom.group.config.configureGroupModule
import com.gvart.parleyroom.homework.config.configureHomeworkModule
import com.gvart.parleyroom.lesson.config.configureLessonModule
import com.gvart.parleyroom.material.config.configureMaterialModule
import com.gvart.parleyroom.notification.config.configureNotificationModule
import com.gvart.parleyroom.registration.routing.configureRegistrationModule
import com.gvart.parleyroom.library.config.configureLibraryModule
import com.gvart.parleyroom.topic.config.configureTopicModule
import com.gvart.parleyroom.user.config.configureUserModule
import com.gvart.parleyroom.video.config.configureVideoModule
import com.gvart.parleyroom.vocabulary.config.configureVocabularyModule
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module(dataSource: javax.sql.DataSource? = null) {
    generalConfig()
    configureDatabase(dataSource)
    configureRegistrationModule()
    configureUserModule()
    configureAdminModule()
    configureNotificationModule()
    configureVideoModule()
    configureTopicModule()
    configureGroupModule()
    configureLessonModule()
    configureAvailabilityModule()
    configureVocabularyModule()
    configureGoalModule()
    configureMaterialModule()
    configureDocumentModule()
    // After materials (storage) and documents (DocumentSupport), which homework uses.
    configureHomeworkModule()
    configureLibraryModule()
    configureAiModule()
    configureActivityModule()
    configureHealthRouting()
}

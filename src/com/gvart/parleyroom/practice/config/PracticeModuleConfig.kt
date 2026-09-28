package com.gvart.parleyroom.practice.config

import com.gvart.parleyroom.practice.routing.configurePracticeRouting
import com.gvart.parleyroom.practice.service.PracticeService
import com.gvart.parleyroom.practice.service.SentenceService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import kotlin.time.Duration

data class PracticeConfig(
    val newCardsPerDay: Int,
    /** FSRS stability (days) from which a REVIEW card counts as LEARNED. */
    val learnedStabilityDays: Double,
    val sentencesPerDay: Int,
    val sentenceTimeout: Duration,
)

fun Application.configurePracticeModule() {
    val config = environment.config
    fun value(path: String, default: String) = config.propertyOrNull(path)?.getString() ?: default

    val practiceConfig = PracticeConfig(
        newCardsPerDay = value("practice.new_cards_per_day", "15").toInt(),
        learnedStabilityDays = value("practice.learned_stability_days", "21").toDouble(),
        sentencesPerDay = value("practice.sentences_per_day", "30").toInt(),
        sentenceTimeout = Duration.parse(value("practice.sentence_timeout", "30s")),
    )

    dependencies {
        provide { practiceConfig }
        provide(PracticeService::class)
        provide(SentenceService::class)
    }

    configurePracticeRouting()
}

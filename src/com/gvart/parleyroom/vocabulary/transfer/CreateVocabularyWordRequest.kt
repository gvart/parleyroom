package com.gvart.parleyroom.vocabulary.transfer

import com.gvart.parleyroom.vocabulary.data.VocabCategory
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class CreateVocabularyWordRequest(
    val studentId: String,
    val lessonId: String? = null,
    val german: String,
    val english: String,
    val exampleSentence: String? = null,
    val exampleTranslation: String? = null,
    val category: VocabCategory,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (studentId.isBlank()) add("Student ID can't be empty")
            if (german.isBlank()) add("German word can't be empty")
            if (english.isBlank()) add("English translation can't be empty")
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

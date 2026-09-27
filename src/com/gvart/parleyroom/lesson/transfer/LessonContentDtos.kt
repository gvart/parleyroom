package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.WordType
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

/**
 * PATCH body for the teacher-authored lesson content. Null fields are left unchanged;
 * lists replace the current links. `clearGroup = true` unlinks the lesson from its group.
 */
@Serializable
data class UpdateLessonContentRequest(
    val rawNotes: String? = null,
    val promptUsed: String? = null,
    val groupId: String? = null,
    val clearGroup: Boolean = false,
    val topicIds: List<String>? = null,
    val grammarTopicIds: List<String>? = null,
    val vocabEntryIds: List<String>? = null,
    val correctedSentences: List<CorrectedSentenceInput>? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (groupId != null && clearGroup) add("Pass either groupId or clearGroup, not both")
            correctedSentences?.forEachIndexed { i, s ->
                if (s.incorrect.isBlank() || s.correct.isBlank()) add("correctedSentences[$i] needs both incorrect and correct")
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}

@Serializable
data class CorrectedSentenceInput(
    val incorrect: String,
    val correct: String,
)

@Serializable
data class CorrectedSentenceResponse(
    val id: String,
    val incorrect: String,
    val correct: String,
)

@Serializable
data class LessonVocabRef(
    val id: String,
    val lemma: String,
    val article: NounArticle? = null,
    val plural: String? = null,
    val wordType: WordType,
)

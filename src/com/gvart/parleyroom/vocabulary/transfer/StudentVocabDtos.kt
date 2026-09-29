package com.gvart.parleyroom.vocabulary.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.WordType
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * A word in a student's vocabulary. For students, [translations] and [explanationDe]
 * contain only what [display] allows; [revealTranslations] carries the hidden
 * translations when the student may reveal them on demand. Teachers and admins
 * always get every field ([revealTranslations] is then null).
 */
@Serializable
data class StudentVocabResponse(
    val id: String,
    val studentId: String,
    val entryId: String,
    val lemma: String,
    val article: NounArticle? = null,
    val plural: String? = null,
    val wordType: WordType,
    val forms: String? = null,
    val government: String? = null,
    val exampleSentence: String? = null,
    val level: LanguageLevel? = null,
    val topicIds: List<String> = emptyList(),
    val grammarTopicIds: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val lessonId: String? = null,
    val status: StudentVocabStatus,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val due: OffsetDateTime? = null,
    val reps: Int,
    val lapses: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastReview: OffsetDateTime? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val addedAt: OffsetDateTime,
    val display: VocabDisplaySetting,
    val translations: Map<String, String> = emptyMap(),
    val explanationDe: String? = null,
    val revealTranslations: Map<String, String>? = null,
)

@Serializable
data class StudentVocabPageResponse(
    val words: List<StudentVocabResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

/** Which fields a student sees: any of "ru", "uk", "en", "de_explanation". */
@Serializable
data class VocabDisplaySetting(
    val fields: List<String>,
    val allowTranslationToggle: Boolean,
) {
    fun validate(): ValidationResult =
        if (fields.isEmpty()) ValidationResult.Invalid("At least one display field is required") else ValidationResult.Valid
}

@Serializable
data class VocabSettingsResponse(
    val studentId: String,
    val teacherId: String,
    val level: LanguageLevel? = null,
    /** The student's translation language (ru | uk | en). */
    val nativeLanguage: String? = null,
    val fields: List<String>,
    val allowTranslationToggle: Boolean,
    /** True when no explicit setting is stored and the level-based default applies. */
    val isDefault: Boolean,
)

@Serializable
data class SetStudentLevelRequest(
    val level: LanguageLevel,
)

@Serializable
data class SetStudentNativeLanguageRequest(
    /** ru | uk | en */
    val nativeLanguage: String,
)

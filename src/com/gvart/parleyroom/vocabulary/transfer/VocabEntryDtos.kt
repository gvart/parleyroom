package com.gvart.parleyroom.vocabulary.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.WordType
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** Editable fields of a library entry; used for create, full replace and quick-add. */
@Serializable
data class VocabEntryInput(
    val lemma: String,
    val article: NounArticle? = null,
    val plural: String? = null,
    val wordType: WordType,
    val forms: String? = null,
    val government: String? = null,
    val translations: Map<String, String> = emptyMap(),
    val explanationDe: String? = null,
    val exampleSentence: String? = null,
    val level: LanguageLevel? = null,
    val topicIds: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val sourceLessonId: String? = null,
) {
    fun errors(): List<String> = buildList {
        if (lemma.isBlank()) add("Lemma can't be empty")
        if (lemma.length > 255) add("Lemma must be at most 255 characters")
        if (article != null && wordType != WordType.NOUN) add("Only nouns can have an article")
    }

    fun validate(): ValidationResult = errors().let {
        if (it.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(it)
    }
}

@Serializable
data class VocabEntryResponse(
    val id: String,
    val teacherId: String,
    val lemma: String,
    val article: NounArticle? = null,
    val plural: String? = null,
    val wordType: WordType,
    val forms: String? = null,
    val government: String? = null,
    val translations: Map<String, String> = emptyMap(),
    val explanationDe: String? = null,
    val exampleSentence: String? = null,
    val level: LanguageLevel? = null,
    val topicIds: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val sourceLessonId: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class VocabEntryPageResponse(
    val entries: List<VocabEntryResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

/**
 * Target students for an entry. Union of [studentIds] and the members of [groupId];
 * when both are empty and [lessonId] is set, the lesson's confirmed students are used.
 */
@Serializable
data class AssignVocabRequest(
    val studentIds: List<String> = emptyList(),
    val groupId: String? = null,
    val lessonId: String? = null,
)

@Serializable
data class AssignVocabResponse(
    val assigned: Int,
    val skipped: Int,
)

/** Teacher quick-add: find-or-create the entry in the library, then assign it. */
@Serializable
data class QuickAddVocabRequest(
    val entry: VocabEntryInput,
    val studentIds: List<String> = emptyList(),
    val groupId: String? = null,
    val lessonId: String? = null,
) {
    fun validate(): ValidationResult = entry.validate()
}

@Serializable
data class QuickAddVocabResponse(
    val entry: VocabEntryResponse,
    /** True when an existing library entry matched the dedupe key and was reused. */
    val reused: Boolean,
    val assigned: Int,
    val skipped: Int,
)

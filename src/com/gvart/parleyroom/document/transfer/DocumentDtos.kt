package com.gvart.parleyroom.document.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import java.time.OffsetDateTime

/** Full replace of a document's content and tags (the autosave target). */
@Serializable
data class DocumentInput(
    val title: String,
    val level: LanguageLevel? = null,
    val topicIds: List<String> = emptyList(),
    val grammarTopicIds: List<String> = emptyList(),
    val audience: DocumentAudience,
    val blocks: JsonArray,
) {
    fun validate(): ValidationResult = validateTitle(title)
}

@Serializable
data class CreateDocumentRequest(
    val title: String,
    val level: LanguageLevel? = null,
    val topicIds: List<String> = emptyList(),
    val grammarTopicIds: List<String> = emptyList(),
    val audience: DocumentAudience,
    val blocks: JsonArray = JsonArray(emptyList()),
    val studentIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val lessonIds: List<String> = emptyList(),
    val createdFromLessonId: String? = null,
) {
    fun validate(): ValidationResult = validateTitle(title)

    fun toInput() = DocumentInput(title, level, topicIds, grammarTopicIds, audience, blocks)
}

@Serializable
data class DuplicateDocumentRequest(
    val title: String? = null,
) {
    fun validate(): ValidationResult = title?.let(::validateTitle) ?: ValidationResult.Valid
}

@Serializable
data class DocumentShareRequest(
    val studentIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
)

@Serializable
data class LinkLessonDocumentRequest(
    val documentId: String,
)

@Serializable
data class DocumentResponse(
    val id: String,
    val ownerId: String,
    val title: String,
    val level: LanguageLevel? = null,
    val topicIds: List<String> = emptyList(),
    val grammarTopicIds: List<String> = emptyList(),
    val audience: DocumentAudience,
    val studentIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val lessonIds: List<String> = emptyList(),
    val createdFromLessonId: String? = null,
    val blocks: JsonArray,
    val vocab: List<DocumentVocabEntry> = emptyList(),
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class DocumentSummary(
    val id: String,
    val ownerId: String,
    val title: String,
    val level: LanguageLevel? = null,
    val topicIds: List<String> = emptyList(),
    val grammarTopicIds: List<String> = emptyList(),
    val audience: DocumentAudience,
    val studentIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val lessonIds: List<String> = emptyList(),
    val createdFromLessonId: String? = null,
    val blockCount: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class DocumentPageResponse(
    val documents: List<DocumentSummary>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

/**
 * A library entry referenced by a vocab_table row, rendered for the viewer. For students,
 * [translations] / [explanationDe] only contain what [display] allows and [revealTranslations]
 * holds the hidden translations when the toggle is allowed. Teachers get every field and no [display].
 */
@Serializable
data class DocumentVocabEntry(
    val id: String,
    val lemma: String,
    val article: NounArticle? = null,
    val plural: String? = null,
    val wordType: WordType,
    val forms: String? = null,
    val government: String? = null,
    val exampleSentence: String? = null,
    val level: LanguageLevel? = null,
    val display: VocabDisplaySetting? = null,
    val translations: Map<String, String> = emptyMap(),
    val explanationDe: String? = null,
    val revealTranslations: Map<String, String>? = null,
)

@Serializable
data class DocumentVersionSummary(
    val id: String,
    val number: Int,
    val reason: DocumentVersionReason,
    val title: String,
    val blockCount: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

@Serializable
data class DocumentVersionResponse(
    val id: String,
    val documentId: String,
    val number: Int,
    val reason: DocumentVersionReason,
    val title: String,
    val blocks: JsonArray,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

/** A document linked to a lesson, as listed in `LessonResponse.documents`. */
@Serializable
data class LessonDocumentRef(
    val id: String,
    val title: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

private fun validateTitle(title: String): ValidationResult = when {
    title.isBlank() -> ValidationResult.Invalid("Title can't be empty")
    title.trim().length > 255 -> ValidationResult.Invalid("Title is longer than 255 characters")
    else -> ValidationResult.Valid
}

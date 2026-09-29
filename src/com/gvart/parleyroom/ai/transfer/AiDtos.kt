package com.gvart.parleyroom.ai.transfer

import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.PromptTemplateLessonType
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.document.transfer.DocumentSummary
import com.gvart.parleyroom.material.data.MaterialSkill
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.topic.transfer.GrammarTopicRef
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

enum class DisplaySource { LESSON, STUDENT, LEVEL_DEFAULT }

/** What the teacher sent to start a job (stored as `generation_jobs.input`). */
@Serializable
data class JobInput(
    val notes: String? = null,
    val prompt: String? = null,
    val promptTemplateId: String? = null,
    val instruction: String? = null,
    val entryIds: List<String>? = null,
    val fields: List<String>? = null,
    /** REFINE of one bundle item. */
    val itemId: String? = null,
    val pastLessonIds: List<String>? = null,
    val topicIds: List<String>? = null,
    val grammarTopicIds: List<String>? = null,
    val kinds: List<DraftKind>? = null,
)

@Serializable
data class JobError(val code: String, val message: String)

@Serializable
data class JobUsage(val inputTokens: Int? = null, val outputTokens: Int? = null)

@Serializable
data class GenerationJobResponse(
    val id: String,
    val kind: GenerationJobKind,
    val status: GenerationJobStatus,
    val lessonId: String? = null,
    /** SUGGEST_TAGS: the material whose tags are suggested. */
    val materialId: String? = null,
    val parentJobId: String? = null,
    val documentId: String? = null,
    /** GENERATE / REFINE of a draft bundle. */
    val bundleId: String? = null,
    val input: JobInput,
    val result: JsonElement? = null,
    val error: JobError? = null,
    val model: String? = null,
    val attempts: Int,
    val usage: JobUsage? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startedAt: OffsetDateTime? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val finishedAt: OffsetDateTime? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val publishedAt: OffsetDateTime? = null,
)

// ---- Results ----

enum class TextSourceKind { PDF, DOCX, TEXT, NAME_ONLY }

/** What was sent to the model for a SUGGEST_TAGS job. */
@Serializable
data class TextSource(val kind: TextSourceKind, val chars: Int, val truncated: Boolean)

@Serializable
data class SuggestedTopic(val name: String, val parentName: String? = null, val existingId: String? = null)

@Serializable
data class SuggestedGrammarTopic(val name: String, val level: LanguageLevel? = null, val existingId: String? = null)

/** SUGGEST_TAGS result: suggestions only, applied by the teacher via PUT /materials/{id}. */
@Serializable
data class SuggestTagsResult(
    val level: LanguageLevel? = null,
    val skill: MaterialSkill? = null,
    val topics: List<SuggestedTopic>,
    val grammarTopics: List<SuggestedGrammarTopic>,
    val source: TextSource,
)

@Serializable
data class AiStatusResponse(val available: Boolean)

// ---- Requests ----

@Serializable
data class RefineRequest(val instruction: String) {
    fun validate(): ValidationResult = when {
        instruction.isBlank() -> ValidationResult.Invalid("instruction can't be empty")
        instruction.length > 4_000 -> ValidationResult.Invalid("instruction must be at most 4000 characters")
        else -> ValidationResult.Valid
    }
}

@Serializable
data class FillMissingRequest(
    val entryIds: List<String>,
    val fields: List<String>,
) {
    fun validate(): ValidationResult = when {
        entryIds.isEmpty() || entryIds.size > 100 -> ValidationResult.Invalid("entryIds must contain 1..100 ids")
        fields.isEmpty() -> ValidationResult.Invalid("fields can't be empty")
        else -> ValidationResult.Valid
    }
}

@Serializable
data class MissingFieldsResponse(val count: Int, val entryIds: List<String>)

// ---- Panel state ----

@Serializable
data class AttendeeRef(val id: String, val firstName: String, val lastName: String)

/** Grammar the AI is told to target (P8): names at the context level, lists capped, counts not. */
@Serializable
data class GrammarGaps(
    val needsWork: List<String>,
    val notCovered: List<String>,
    val needsWorkCount: Int,
    val notCoveredCount: Int,
) {
    companion object {
        val NONE = GrammarGaps(emptyList(), emptyList(), 0, 0)
    }
}

@Serializable
data class ContextSummary(
    val level: LanguageLevel? = null,
    val display: VocabDisplaySetting,
    val displaySource: DisplaySource,
    val lessonOverrideActive: Boolean,
    val knownWordCount: Int,
    val coveredGrammar: List<GrammarTopicRef>,
    val grammarGaps: GrammarGaps,
    val libraryTopicCount: Int,
    val libraryGrammarTopicCount: Int,
    val attendees: List<AttendeeRef>,
    val attendeeCount: Int,
    /** Active goals of the student (1:1 only), e.g. "Exam telc B1 by 2026-12-01". */
    val goals: List<String> = emptyList(),
    /** Words the student keeps forgetting (FSRS lapses / difficulty), 1:1 only. */
    val weakWords: List<String> = emptyList(),
    /** Earlier lessons whose notes are sent as context. */
    val pastLessons: List<PastLessonRef> = emptyList(),
    val focusTopics: List<String> = emptyList(),
    val focusGrammarTopics: List<String> = emptyList(),
)

// ---- Prompt templates ----

@Serializable
data class PromptTemplateInput(
    val name: String,
    val text: String,
    val level: LanguageLevel? = null,
    val lessonType: PromptTemplateLessonType? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (name.isBlank() || name.trim().length > 100) add("name must be 1..100 characters")
            if (text.isBlank() || text.length > 10_000) add("text must be 1..10000 characters")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}

@Serializable
data class PromptTemplateResponse(
    val id: String,
    val teacherId: String,
    val name: String,
    val text: String,
    val level: LanguageLevel? = null,
    val lessonType: PromptTemplateLessonType? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

// ---- Library suggestions ----

enum class SuggestionKind { TOPIC, GRAMMAR }

@Serializable
data class SuggestionSummary(
    val kind: SuggestionKind,
    val id: String,
    val name: String,
    val level: LanguageLevel? = null,
    val documentCount: Int,
    val materialCount: Int,
)

@Serializable
data class SuggestedDocument(
    val document: DocumentSummary,
    val matchedTopicIds: List<String>,
    val matchedGrammarTopicIds: List<String>,
)

@Serializable
data class SuggestedMaterial(
    val material: MaterialResponse,
    val matchedTopicIds: List<String>,
    val matchedGrammarTopicIds: List<String>,
)

@Serializable
data class LibrarySuggestions(
    val level: LanguageLevel? = null,
    val summary: List<SuggestionSummary>,
    val documents: List<SuggestedDocument>,
    val materials: List<SuggestedMaterial>,
)

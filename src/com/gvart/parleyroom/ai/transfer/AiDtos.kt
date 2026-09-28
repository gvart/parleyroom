package com.gvart.parleyroom.ai.transfer

import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.PromptTemplateLessonType
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.document.transfer.DocumentSummary
import com.gvart.parleyroom.lesson.transfer.CorrectedSentenceInput
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.topic.transfer.GrammarTopicRef
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.time.OffsetDateTime

enum class NachbereitungMode { ONE_ON_ONE, CLUB }
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
    val parentJobId: String? = null,
    val documentId: String? = null,
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

@Serializable
data class NachbereitungResult(
    val documentId: String,
    val vocab: List<ReviewVocabItem>,
    val vocabTables: List<VocabTableRef>,
    val topics: List<TopicProposal>,
    val grammarTopics: List<GrammarTopicProposal>,
    val correctedSentences: List<CorrectedSentenceInput>,
    /** Vocab key -> library entry id, filled by publish. */
    val publishedEntries: Map<String, String> = emptyMap(),
)

@Serializable
data class ReviewVocabItem(
    val key: String,
    val entry: VocabEntryInput,
    val topicKey: String? = null,
    val matchedEntryId: String? = null,
    val matchedEntry: VocabEntryResponse? = null,
    val alreadyAssigned: Boolean = false,
    val selected: Boolean = true,
)

@Serializable
data class VocabTableRef(val blockId: String, val vocabKeys: List<String>)

@Serializable
data class TopicProposal(val key: String, val name: String, val parentName: String? = null, val existingId: String? = null)

@Serializable
data class GrammarTopicProposal(val key: String, val name: String, val level: LanguageLevel? = null, val existingId: String? = null)

@Serializable
data class FillTranslationsResult(val updated: List<FilledEntry>, val skipped: List<String>)

@Serializable
data class FilledEntry(val entryId: String, val filled: List<String>)

// ---- Requests ----

@Serializable
data class GenerateRequest(
    val notes: String,
    val prompt: String = "",
    val promptTemplateId: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (notes.isBlank()) add("notes can't be empty")
            if (notes.length > MAX_NOTES) add("notes must be at most $MAX_NOTES characters")
            if (prompt.length > MAX_PROMPT) add("prompt must be at most $MAX_PROMPT characters")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    companion object {
        const val MAX_NOTES = 20_000
        const val MAX_PROMPT = 10_000
    }
}

@Serializable
data class RefineRequest(val instruction: String) {
    fun validate(): ValidationResult = when {
        instruction.isBlank() -> ValidationResult.Invalid("instruction can't be empty")
        instruction.length > 4_000 -> ValidationResult.Invalid("instruction must be at most 4000 characters")
        else -> ValidationResult.Valid
    }
}

@Serializable
data class ReviewUpdateRequest(val vocab: List<ReviewVocabItem>) {
    fun validate(): ValidationResult {
        val errors = vocab.flatMapIndexed { i, item -> item.entry.errors().map { "vocab[$i]: $it" } }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}

@Serializable
data class PublishVocabItem(
    val key: String,
    val matchedEntryId: String? = null,
    val entry: VocabEntryInput? = null,
    val topicKeys: List<String> = emptyList(),
)

@Serializable
data class PublishTopic(
    val key: String,
    val name: String,
    val parentId: String? = null,
    val parentKey: String? = null,
)

@Serializable
data class PublishGrammarTopic(
    val key: String,
    val name: String,
    val level: LanguageLevel? = null,
)

@Serializable
data class PublishRequest(
    val jobId: String,
    val vocab: List<PublishVocabItem> = emptyList(),
    val topics: List<PublishTopic> = emptyList(),
    val grammarTopics: List<PublishGrammarTopic> = emptyList(),
    val topicIds: List<String> = emptyList(),
    val grammarTopicIds: List<String> = emptyList(),
    val correctedSentences: List<CorrectedSentenceInput>? = null,
    val share: Boolean = true,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            vocab.forEachIndexed { i, item ->
                if (item.matchedEntryId == null && item.entry == null) add("vocab[$i] needs matchedEntryId or entry")
                item.entry?.errors()?.forEach { add("vocab[$i]: $it") }
            }
            if (vocab.map { it.key }.toSet().size != vocab.size) add("vocab keys must be unique")
            topics.forEachIndexed { i, t ->
                if (t.name.isBlank() || t.name.length > 255) add("topics[$i].name must be 1..255 characters")
                if (t.parentId != null && t.parentKey != null) add("topics[$i]: pass parentId or parentKey, not both")
            }
            grammarTopics.forEachIndexed { i, g -> if (g.name.isBlank() || g.name.length > 255) add("grammarTopics[$i].name must be 1..255 characters") }
            correctedSentences?.forEachIndexed { i, s ->
                if (s.incorrect.isBlank() || s.correct.isBlank()) add("correctedSentences[$i] needs both incorrect and correct")
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}

@Serializable
data class PublishedRef(val key: String, val id: String, val reused: Boolean)

@Serializable
data class PublishedVocab(val key: String, val entryId: String, val reused: Boolean)

@Serializable
data class PublishResponse(
    val documentId: String,
    val revision: Int,
    val wordsCreated: Int,
    val wordsReused: Int,
    val wordsAssigned: Int,
    val recipients: Int,
    val recipientIds: List<String>,
    val topicsCreated: Int,
    val grammarTopicsCreated: Int,
    val vocab: List<PublishedVocab>,
    val topics: List<PublishedRef>,
    val grammarTopics: List<PublishedRef>,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val publishedAt: OffsetDateTime,
)

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

@Serializable
data class ContextSummary(
    val level: LanguageLevel? = null,
    val display: VocabDisplaySetting,
    val displaySource: DisplaySource,
    val lessonOverrideActive: Boolean,
    val knownWordCount: Int,
    val coveredGrammar: List<GrammarTopicRef>,
    val libraryTopicCount: Int,
    val libraryGrammarTopicCount: Int,
    val attendees: List<AttendeeRef>,
    val attendeeCount: Int,
)

@Serializable
data class NachbereitungState(
    val lessonId: String,
    val mode: NachbereitungMode,
    val aiAvailable: Boolean,
    val notes: String? = null,
    val prompt: String? = null,
    val context: ContextSummary,
    val latestJob: GenerationJobResponse? = null,
    val draftDocumentId: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val publishedAt: OffsetDateTime? = null,
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

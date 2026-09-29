package com.gvart.parleyroom.ai.transfer

import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.DraftMode
import com.gvart.parleyroom.ai.data.DraftScope
import com.gvart.parleyroom.ai.data.DraftStatus
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.transfer.CreateAssignmentRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import java.time.OffsetDateTime

// ---- Tags ----

/** A topic tag: [id] = an existing library topic; without it Send creates [name] (under [parentName] if found). */
@Serializable
data class DraftTopic(val id: String? = null, val name: String, val parentName: String? = null)

/** A grammar tag: [id] = an existing library grammar topic; without it Send creates [name]. */
@Serializable
data class DraftGrammarTopic(val id: String? = null, val name: String, val level: LanguageLevel? = null)

// ---- Item payloads ----

@Serializable
data class DraftWord(
    /** The word's display fields; `entry.topicIds` is ignored, tags live in [topics]. */
    val entry: VocabEntryInput,
    val topics: List<DraftTopic> = emptyList(),
    val grammarTopics: List<DraftGrammarTopic> = emptyList(),
    /** Read-only: the library entry with the same lemma + article + word type (Send reuses it as is). */
    val matchedEntryId: String? = null,
    /** Read-only: every recipient already has the word. */
    val alreadyAssigned: Boolean = false,
)

/** EXERCISE_DOCUMENT (homework) or NOTES_DOCUMENT (club). Blocks are real document blocks; vocab_table is not allowed. */
@Serializable
data class DraftDocument(
    val title: String,
    val blocks: JsonArray,
    val topics: List<DraftTopic> = emptyList(),
    val grammarTopics: List<DraftGrammarTopic> = emptyList(),
    /** The created document's level; default the learner's level at generation. */
    val level: LanguageLevel? = null,
)

@Serializable
data class DraftTask(
    val title: String,
    val instructions: String,
    val responseType: HomeworkResponseType,
    val topics: List<DraftTopic> = emptyList(),
    val grammarTopics: List<DraftGrammarTopic> = emptyList(),
)

@Serializable
data class DraftItemResponse(
    val id: String,
    val kind: DraftItemKind,
    val position: Int,
    val approved: Boolean,
    val word: DraftWord? = null,
    val document: DraftDocument? = null,
    val task: DraftTask? = null,
    /** WORD with a library match: the library version, so the UI can show what Send will reuse. */
    val matchedEntry: VocabEntryResponse? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

// ---- Bundle ----

/** Body of POST …/draft-bundles, stored as the bundle's input. */
@Serializable
data class GenerateDraftRequest(
    /** Lesson scope: the current notes (default: the lesson's raw_notes). Never written to the lesson. */
    val notes: String? = null,
    val prompt: String = "",
    /** The template's text is put before [prompt]. */
    val promptTemplateId: String? = null,
    /** Earlier lessons whose notes are context; null = the latest earlier lesson with notes, [] = none. */
    val pastLessonIds: List<String>? = null,
    /** Library topics / grammar the teacher wants to focus on. */
    val topicIds: List<String> = emptyList(),
    val grammarTopicIds: List<String> = emptyList(),
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if ((notes?.length ?: 0) > MAX_NOTES) add("notes must be at most $MAX_NOTES characters")
            if (prompt.length > MAX_PROMPT) add("prompt must be at most $MAX_PROMPT characters")
            if ((pastLessonIds?.size ?: 0) > MAX_PAST_LESSONS) add("pastLessonIds must have at most $MAX_PAST_LESSONS entries")
            if (topicIds.size > MAX_FOCUS || grammarTopicIds.size > MAX_FOCUS) add("at most $MAX_FOCUS focus topics / grammar topics")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    companion object {
        const val MAX_NOTES = 20_000
        const val MAX_PROMPT = 10_000
        const val MAX_PAST_LESSONS = 5
        const val MAX_FOCUS = 20
    }
}

@Serializable
data class DraftBundleResponse(
    val id: String,
    val scope: DraftScope,
    val mode: DraftMode,
    val status: DraftStatus,
    val lessonId: String? = null,
    val studentId: String? = null,
    val input: GenerateDraftRequest,
    /** The latest GENERATE / REFINE job of the bundle (poll it while QUEUED / RUNNING). */
    val job: GenerationJobResponse? = null,
    val items: List<DraftItemResponse>,
    val approvedCount: Int,
    /** Who Send targets by default: the lesson's confirmed attendees, or the student. */
    val recipients: List<AttendeeRef>,
    val sendResult: SendDraftResponse? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val sentAt: OffsetDateTime? = null,
)

@Serializable
data class DraftBundleSummary(
    val id: String,
    val scope: DraftScope,
    val mode: DraftMode,
    val status: DraftStatus,
    val lessonId: String? = null,
    val lessonTitle: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lessonScheduledAt: OffsetDateTime? = null,
    val student: AttendeeRef? = null,
    val itemCount: Int,
    val approvedCount: Int,
    val job: GenerationJobResponse? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val sentAt: OffsetDateTime? = null,
)

/** PATCH an item: any subset. The content field must match the item's kind and replaces its content. */
@Serializable
data class PatchDraftItemRequest(
    val approved: Boolean? = null,
    val word: DraftWord? = null,
    val document: DraftDocument? = null,
    val task: DraftTask? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (listOfNotNull(word, document, task).size > 1) add("send at most one of word, document, task")
            word?.entry?.errors()?.forEach { add("word: $it") }
            word?.let { addAll(tagErrors("word", it.topics, it.grammarTopics)) }
            document?.let {
                if (it.title.isBlank() || it.title.length > 255) add("document.title must be 1..255 characters")
                addAll(tagErrors("document", it.topics, it.grammarTopics))
            }
            task?.let {
                if (it.title.isBlank() || it.title.length > 255) add("task.title must be 1..255 characters")
                if (it.instructions.length > CreateAssignmentRequest.MAX_INSTRUCTIONS) add("task.instructions too long")
                addAll(tagErrors("task", it.topics, it.grammarTopics))
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun tagErrors(field: String, topics: List<DraftTopic>, grammar: List<DraftGrammarTopic>) = buildList {
        if (topics.size > MAX_TAGS || grammar.size > MAX_TAGS) add("$field: at most $MAX_TAGS topics and $MAX_TAGS grammar topics")
        if ((topics.map { it.name } + grammar.map { it.name }).any { it.isBlank() || it.length > 255 })
            add("$field: tag names must be 1..255 characters")
    }

    companion object {
        const val MAX_TAGS = 10
    }
}

/** Refine the whole bundle, or only [itemId]. */
@Serializable
data class RefineDraftRequest(val instruction: String, val itemId: String? = null) {
    fun validate(): ValidationResult = when {
        instruction.isBlank() -> ValidationResult.Invalid("instruction can't be empty")
        instruction.length > 4_000 -> ValidationResult.Invalid("instruction must be at most 4000 characters")
        else -> ValidationResult.Valid
    }
}

@Serializable
data class SendDraftRequest(
    /** A subset of the bundle's recipients; null = all of them. */
    val studentIds: List<String>? = null,
    /** YYYY-MM-DD; default today + 7 days. */
    val dueDate: String? = null,
    /** Assignment title; default the homework document's title (or the first task's). */
    val title: String? = null,
    val instructions: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (dueDate != null && runCatching { LocalDate.parse(dueDate) }.isFailure) add("dueDate must be YYYY-MM-DD")
            if (title != null && (title.isBlank() || title.length > 255)) add("title must be 1..255 characters")
            if ((instructions?.length ?: 0) > CreateAssignmentRequest.MAX_INSTRUCTIONS) add("instructions too long")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}

@Serializable
data class SentWord(val itemId: String, val entryId: String, val reused: Boolean)

@Serializable
data class SendDraftResponse(
    val bundleId: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val sentAt: OffsetDateTime,
    val recipientIds: List<String>,
    val words: List<SentWord>,
    /** New student-vocab rows (words a recipient already had are not counted). */
    val wordsAssigned: Int,
    val exerciseDocumentId: String? = null,
    val notesDocumentId: String? = null,
    val assignmentId: String? = null,
    val sentItemIds: List<String>,
    /** Unapproved items: left in the bundle, not sent. */
    val skippedItemIds: List<String>,
    val topicsCreated: Int,
    val grammarTopicsCreated: Int,
)

// ---- Context for the generate form ----

@Serializable
data class PastLessonRef(
    val id: String,
    val title: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    /** The lesson's notes as plain text, first 300 characters. */
    val notesPreview: String,
)

@Serializable
data class DraftContextResponse(
    val scope: DraftScope,
    val mode: DraftMode,
    val aiAvailable: Boolean,
    val lessonId: String? = null,
    val studentId: String? = null,
    /** Lesson scope: lessons.raw_notes. */
    val notes: String? = null,
    /** What the server adds to the prompt with the default sources. */
    val context: ContextSummary,
    /** Earlier (not cancelled) lessons with notes, newest first: the "notes from past lessons" picker. */
    val pastLessons: List<PastLessonRef>,
    /** The open (DRAFT) bundle of this lesson / student, if any. */
    val openBundle: DraftBundleSummary? = null,
)

// ---- Fill-missing proposals ----

enum class FillProposalStatus { PENDING, APPLIED, REJECTED }

@Serializable
data class FillProposal(
    val id: String,
    val entryId: String,
    val lemma: String,
    /** ru | en | de_explanation */
    val field: String,
    val currentValue: String? = null,
    val proposedValue: String,
)

/** FILL_TRANSLATIONS result: proposals only; vocab_entries change only through apply. */
@Serializable
data class FillProposalsResult(
    val proposals: List<FillProposal>,
    val skipped: List<String>,
    val status: FillProposalStatus = FillProposalStatus.PENDING,
    /** Proposal ids written by apply. */
    val applied: List<String> = emptyList(),
    /** Selected proposal ids not written because the field was filled meanwhile. */
    val stale: List<String> = emptyList(),
    @Serializable(with = OffsetDateTimeSerializer::class)
    val resolvedAt: OffsetDateTime? = null,
)

@Serializable
data class FillProposalDecision(val id: String, val value: String? = null)

@Serializable
data class ApplyFillProposalsRequest(val proposals: List<FillProposalDecision>) {
    fun validate(): ValidationResult = when {
        proposals.map { it.id }.toSet().size != proposals.size -> ValidationResult.Invalid("proposal ids must be unique")
        proposals.any { it.value != null && (it.value.isBlank() || it.value.length > 2_000) } ->
            ValidationResult.Invalid("value must be 1..2000 characters")
        else -> ValidationResult.Valid
    }
}

// ---- Document draft revisions ----

@Serializable
data class DocumentDraftInput(val title: String, val blocks: JsonArray) {
    fun validate(): ValidationResult =
        if (title.isBlank() || title.length > 255) ValidationResult.Invalid("title must be 1..255 characters") else ValidationResult.Valid
}

@Serializable
data class DocumentDraftResponse(
    val documentId: String,
    val title: String,
    val blocks: JsonArray,
    /** The document revision the draft was made from; `currentRevision` differs when the document changed since. */
    val baseRevision: Int,
    val currentRevision: Int,
    val jobId: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

package com.gvart.parleyroom.goal.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.RoadmapItemKind
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.SelfAssessmentAnswer
import com.gvart.parleyroom.goal.service.GoalTrackStatus
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

const val MAX_GOAL_TITLE = 200
const val MAX_EXAM = 100
const val MAX_GOAL_TEXT = 4_000
const val MAX_MILESTONE_TITLE = 200
const val MAX_MILESTONE_TEXT = 2_000
const val MAX_LIST_ENTRIES = 30
const val MAX_LIST_ENTRY = 200
const val MAX_ITEM_TITLE = 300
const val MAX_BATCH_ITEMS = 200
const val MAX_COMMENT = 1_000
const val MAX_INSTRUCTION = 1_000

/**
 * Collects field-level reasons. Every reason starts with the property name so the problem's
 * `errors[].pointer` names it (see validationPointer). Enums and dates arrive as strings so a bad
 * value is a field error rather than a malformed body.
 */
private class Reasons {
    val list = mutableListOf<String>()

    fun add(reason: String) { list += reason }

    fun date(name: String, raw: String?): LocalDate? = raw?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { add("$name must be YYYY-MM-DD"); null }
    }

    fun uuid(name: String, raw: String?) {
        if (raw != null && runCatching { UUID.fromString(raw) }.isFailure) add("$name must be a UUID")
    }

    fun <E : Enum<E>> enum(name: String, raw: String?, values: Array<E>) {
        if (raw != null && values.none { it.name == raw }) add("$name must be one of ${values.joinToString()}")
    }

    fun text(name: String, raw: String?, max: Int, required: Boolean = false) {
        when {
            raw == null -> if (required) add("$name is required")
            required && raw.isBlank() -> add("$name can't be empty")
            raw.length > max -> add("$name must be at most $max characters")
        }
    }

    fun list(name: String, raw: List<String>?) {
        if (raw == null) return
        if (raw.size > MAX_LIST_ENTRIES) add("$name must have at most $MAX_LIST_ENTRIES entries")
        if (raw.any { it.isBlank() || it.length > MAX_LIST_ENTRY }) add("$name entries must be 1..$MAX_LIST_ENTRY characters")
    }

    fun result(): ValidationResult = if (list.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(list)
}

private fun validate(block: Reasons.() -> Unit): ValidationResult = Reasons().apply(block).result()

// ---- Goal ----

@Serializable
data class GoalInput(
    val studentId: String? = null,
    val title: String? = null,
    val exam: String? = null,
    val targetLevel: String? = null,
    val startDate: String? = null,
    val deadline: String? = null,
    val lessonsPerWeek: Double? = null,
    val lessonMinutes: Int? = null,
    val levelNote: String? = null,
    val interests: String? = null,
    val materials: String? = null,
) {
    fun validate() = validate {
        if (studentId == null) add("studentId is required") else uuid("studentId", studentId)
        text("title", title, MAX_GOAL_TITLE, required = true)
        text("exam", exam, MAX_EXAM)
        enum("targetLevel", targetLevel, LanguageLevel.entries.toTypedArray())
        val start = if (startDate == null) null.also { add("startDate is required") } else date("startDate", startDate)
        val end = if (deadline == null) null.also { add("deadline is required") } else date("deadline", deadline)
        if (start != null && end != null && end <= start) add("deadline must be after startDate")
        lessons(lessonsPerWeek, lessonMinutes, required = true)
        text("levelNote", levelNote, MAX_GOAL_TEXT)
        text("interests", interests, MAX_GOAL_TEXT)
        text("materials", materials, MAX_GOAL_TEXT)
    }
}

/** Absent fields stay; an empty string clears exam, targetLevel, levelNote, interests and materials. */
@Serializable
data class GoalPatch(
    val title: String? = null,
    val exam: String? = null,
    val targetLevel: String? = null,
    val startDate: String? = null,
    val deadline: String? = null,
    val lessonsPerWeek: Double? = null,
    val lessonMinutes: Int? = null,
    val levelNote: String? = null,
    val interests: String? = null,
    val materials: String? = null,
    val status: String? = null,
) {
    fun validate() = validate {
        if (title != null) text("title", title, MAX_GOAL_TITLE, required = true)
        text("exam", exam, MAX_EXAM)
        if (targetLevel != "") enum("targetLevel", targetLevel, LanguageLevel.entries.toTypedArray())
        val start = date("startDate", startDate)
        val end = date("deadline", deadline)
        if (start != null && end != null && end <= start) add("deadline must be after startDate")
        lessons(lessonsPerWeek, lessonMinutes, required = false)
        text("levelNote", levelNote, MAX_GOAL_TEXT)
        text("interests", interests, MAX_GOAL_TEXT)
        text("materials", materials, MAX_GOAL_TEXT)
        enum("status", status, GoalStatus.entries.toTypedArray())
    }
}

private fun Reasons.lessons(perWeek: Double?, minutes: Int?, required: Boolean) {
    if (perWeek == null) { if (required) add("lessonsPerWeek is required") }
    else if (perWeek < 0.5 || perWeek > 14) add("lessonsPerWeek must be between 0.5 and 14")
    if (minutes == null) { if (required) add("lessonMinutes is required") }
    else if (minutes < 15 || minutes > 480) add("lessonMinutes must be between 15 and 480")
}

@Serializable
data class GoalProgressView(
    /** Weighted share of DONE items; null without any item (status NO_ROADMAP). */
    val percent: Int? = null,
    /** Share of the time from startDate to deadline that has passed (student's timezone). */
    val elapsedPercent: Int,
    val status: GoalTrackStatus,
)

/** A goal in a list (no roadmap). */
@Serializable
data class GoalSummary(
    val id: String,
    val studentId: String,
    val teacherId: String? = null,
    val title: String,
    val exam: String? = null,
    val targetLevel: LanguageLevel? = null,
    val startDate: String,
    val deadline: String,
    val status: GoalStatus,
    val progress: GoalProgressView,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class GoalPageResponse(val goals: List<GoalSummary>, val total: Long, val page: Int, val pageSize: Int)

@Serializable
data class RoadmapItemView(
    val id: String,
    val position: Int,
    val kind: RoadmapItemKind,
    val title: String,
    val state: RoadmapItemState,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val stateChangedAt: OffsetDateTime? = null,
    val lessonId: String? = null,
)

@Serializable
data class SelfAssessmentView(
    val answer: SelfAssessmentAnswer,
    val comment: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    /** The milestone is marked done but the student did not answer YES. */
    val mismatch: Boolean,
)

@Serializable
data class MilestoneView(
    val id: String,
    val position: Int,
    val title: String,
    val startDate: String,
    val endDate: String,
    val weight: Double,
    val topic: String? = null,
    val grammarPoints: List<String>,
    val wordFields: List<String>,
    val examPart: String? = null,
    val teacherTips: String? = null,
    val canDo: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val doneAt: OffsetDateTime? = null,
    /** DONE items / items; null without items. */
    val progressPercent: Int? = null,
    val items: List<RoadmapItemView>,
    val selfAssessment: SelfAssessmentView? = null,
)

@Serializable
data class OpenMismatch(
    val milestoneId: String,
    val milestoneTitle: String,
    val answer: SelfAssessmentAnswer,
    val comment: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val answeredAt: OffsetDateTime,
)

/** The goal's QUEUED or RUNNING roadmap job, so a reloaded page can keep polling it. */
@Serializable
data class RoadmapJobRef(
    val id: String,
    val kind: GenerationJobKind,
    val status: GenerationJobStatus,
    val milestoneId: String? = null,
)

/** The teacher's full view of a goal. */
@Serializable
data class TeacherGoalView(
    val id: String,
    val studentId: String,
    val teacherId: String? = null,
    val title: String,
    val exam: String? = null,
    val targetLevel: LanguageLevel? = null,
    val startDate: String,
    val deadline: String,
    val lessonsPerWeek: Double,
    val lessonMinutes: Int,
    val levelNote: String? = null,
    val interests: String? = null,
    val materials: String? = null,
    val status: GoalStatus,
    val progress: GoalProgressView,
    val milestones: List<MilestoneView>,
    val openMismatches: List<OpenMismatch>,
    val activeJob: RoadmapJobRef? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

// ---- Student view: deliberately minimal (no items, grammar, word fields, tips, exam part or teacher input) ----

@Serializable
data class StudentMilestone(val id: String, val position: Int, val done: Boolean, val current: Boolean)

@Serializable
data class PendingSelfAssessment(val milestoneId: String, val canDo: String)

@Serializable
data class StudentGoalView(
    val id: String,
    val title: String,
    val deadline: String,
    val progressPercent: Int? = null,
    val status: GoalTrackStatus,
    val milestones: List<StudentMilestone>,
    val currentCanDo: String? = null,
    val nextCanDo: String? = null,
    /** Titles of the done milestones, for the collapsed "What we have covered". */
    val coveredTitles: List<String>,
    val pendingSelfAssessment: PendingSelfAssessment? = null,
)

/** GET /goals/my: [goal] is null when the student has no active goal. */
@Serializable
data class MyGoalResponse(val goal: StudentGoalView? = null)

@Serializable
data class SelfAssessmentInput(val answer: String? = null, val comment: String? = null) {
    fun validate() = validate {
        if (answer == null) add("answer is required") else enum("answer", answer, SelfAssessmentAnswer.entries.toTypedArray())
        text("comment", comment, MAX_COMMENT)
    }
}

// ---- Roadmap editing ----

@Serializable
data class MilestoneInput(
    val title: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val weight: Double? = null,
    val topic: String? = null,
    val grammarPoints: List<String>? = null,
    val wordFields: List<String>? = null,
    val examPart: String? = null,
    val teacherTips: String? = null,
    val canDo: String? = null,
    /** 1-based; appended at the end when absent. */
    val position: Int? = null,
) {
    fun validate() = validate {
        text("title", title, MAX_MILESTONE_TITLE, required = true)
        val start = if (startDate == null) null.also { add("startDate is required") } else date("startDate", startDate)
        val end = if (endDate == null) null.also { add("endDate is required") } else date("endDate", endDate)
        if (start != null && end != null && end < start) add("endDate must not be before startDate")
        milestoneFields(weight, topic, grammarPoints, wordFields, examPart, teacherTips)
        text("canDo", canDo, MAX_MILESTONE_TEXT, required = true)
        if (position != null && position < 1) add("position must be at least 1")
    }
}

/** Absent fields stay; an empty string clears topic, examPart and teacherTips. */
@Serializable
data class MilestonePatch(
    val title: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val weight: Double? = null,
    val topic: String? = null,
    val grammarPoints: List<String>? = null,
    val wordFields: List<String>? = null,
    val examPart: String? = null,
    val teacherTips: String? = null,
    val canDo: String? = null,
) {
    fun validate() = validate {
        if (title != null) text("title", title, MAX_MILESTONE_TITLE, required = true)
        val start = date("startDate", startDate)
        val end = date("endDate", endDate)
        if (start != null && end != null && end < start) add("endDate must not be before startDate")
        milestoneFields(weight, topic, grammarPoints, wordFields, examPart, teacherTips)
        if (canDo != null) text("canDo", canDo, MAX_MILESTONE_TEXT, required = true)
    }
}

private fun Reasons.milestoneFields(
    weight: Double?, topic: String?, grammarPoints: List<String>?, wordFields: List<String>?, examPart: String?, teacherTips: String?,
) {
    if (weight != null && (weight <= 0 || weight > 100)) add("weight must be greater than 0 and at most 100")
    text("topic", topic, MAX_MILESTONE_TEXT)
    list("grammarPoints", grammarPoints)
    list("wordFields", wordFields)
    text("examPart", examPart, MAX_MILESTONE_TEXT)
    text("teacherTips", teacherTips, MAX_MILESTONE_TEXT)
}

@Serializable
data class ItemInput(val kind: String? = null, val title: String? = null, val position: Int? = null) {
    fun validate() = validate {
        if (kind == null) add("kind is required") else enum("kind", kind, RoadmapItemKind.entries.toTypedArray())
        text("title", title, MAX_ITEM_TITLE, required = true)
        if (position != null && position < 1) add("position must be at least 1")
    }
}

/** [lessonId]: the lesson in which the item was checked (set along with [state], or alone). */
@Serializable
data class ItemPatch(val kind: String? = null, val title: String? = null, val state: String? = null, val lessonId: String? = null) {
    fun validate() = validate {
        enum("kind", kind, RoadmapItemKind.entries.toTypedArray())
        if (title != null) text("title", title, MAX_ITEM_TITLE, required = true)
        enum("state", state, RoadmapItemState.entries.toTypedArray())
        uuid("lessonId", lessonId)
    }
}

@Serializable
data class MoveItemRequest(val milestoneId: String? = null, val position: Int? = null) {
    fun validate() = validate {
        if (milestoneId == null) add("milestoneId is required") else uuid("milestoneId", milestoneId)
        if (position != null && position < 1) add("position must be at least 1")
    }
}

/** The complete new order: every id of the goal's milestones (or the milestone's items) exactly once. */
@Serializable
data class ReorderRequest(val ids: List<String> = emptyList()) {
    fun validate() = validate {
        if (ids.isEmpty()) add("ids can't be empty")
        ids.forEach { uuid("ids", it) }
        if (ids.toSet().size != ids.size) add("ids must not repeat")
    }
}

@Serializable
data class ItemStateChange(val itemId: String? = null, val state: String? = null)

/** After a lesson: set many item states at once. Idempotent: the same body twice changes nothing more. */
@Serializable
data class BatchItemStatesRequest(val lessonId: String? = null, val items: List<ItemStateChange> = emptyList()) {
    fun validate() = validate {
        uuid("lessonId", lessonId)
        if (items.isEmpty() || items.size > MAX_BATCH_ITEMS) add("items must contain 1..$MAX_BATCH_ITEMS entries")
        if (items.any { it.itemId == null || runCatching { UUID.fromString(it.itemId) }.isFailure }) add("items need a valid itemId each")
        if (items.any { s -> RoadmapItemState.entries.none { it.name == s.state } })
            add("items need a state each: one of ${RoadmapItemState.entries.joinToString()}")
        if (items.mapNotNull { it.itemId }.toSet().size != items.size) add("items must not repeat an itemId")
    }
}

// ---- AI ----

/** [replace]: also when the goal already has a roadmap (it is replaced once the job succeeds). */
@Serializable
data class GenerateRoadmapRequest(val replace: Boolean = false, val instruction: String? = null) {
    fun validate() = validate { text("instruction", instruction, MAX_INSTRUCTION) }
}

@Serializable
data class RegenerateMilestoneRequest(val instruction: String? = null) {
    fun validate() = validate { text("instruction", instruction, MAX_INSTRUCTION) }
}

/** The result of a SUCCEEDED ROADMAP / MILESTONE_REGEN job; the roadmap itself is read from the goal. */
@Serializable
data class RoadmapJobResult(val goalId: String, val milestoneCount: Int, val itemCount: Int, val milestoneId: String? = null)

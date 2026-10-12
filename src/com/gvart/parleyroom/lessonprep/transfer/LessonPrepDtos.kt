package com.gvart.parleyroom.lessonprep.transfer

import com.gvart.parleyroom.ai.transfer.GenerationJobResponse
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.goal.data.RoadmapItemKind
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.transfer.BatchItemStatesRequest
import com.gvart.parleyroom.goal.transfer.ItemStateChange
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lessonprep.data.LessonPlanStatus
import com.gvart.parleyroom.lessonprep.service.LessonPlanRules
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.util.UUID

const val MAX_PREP_NOTE = 4_000
const val MAX_COVERED_NOTE = 4_000

// ---- The plan (stored as lesson_plans.plan; the model answers in the same shape) ----

/**
 * A lesson plan. Enum-like fields are strings so a bad value is a field error (see [LessonPlanRules]).
 * [roadmapItemIds] are roadmap item uuids of the plan's goal.
 */
@Serializable
data class LessonPlanContent(
    val lessonGoal: String = "",
    val timePlan: List<TimePlanPhase> = emptyList(),
    val phrases: List<PlanPhrase> = emptyList(),
    val vocabulary: List<PlanWord> = emptyList(),
    val activities: List<PlanActivity> = emptyList(),
    val skipIfShort: List<String> = emptyList(),
    val suggestedHomework: SuggestedHomework? = null,
    val roadmapItemIds: List<String> = emptyList(),
)

/** phase: WARMUP | INPUT | GRAMMAR | PRACTICE | EXAM_PRACTICE | WRAPUP. */
@Serializable
data class TimePlanPhase(val phase: String = "", val title: String = "", val minutes: Int = 0, val description: String = "")

@Serializable
data class PlanPhrase(val de: String = "", val note: String? = null)

/** article: der | die | das (nouns only). */
@Serializable
data class PlanWord(val lemma: String = "", val article: String? = null, val plural: String? = null, val example: String = "")

/** type: SPEAKING_CARDS | ROLE_PLAY | EXERCISE | GAME | OTHER. */
@Serializable
data class PlanActivity(val type: String = "", val title: String = "", val instructions: String = "", val materialsNeeded: String? = null)

/** [prompt] is meant for the follow-up homework generator (GenerateDraftRequest.prompt). */
@Serializable
data class SuggestedHomework(val summary: String = "", val prompt: String = "")

// ---- Requests ----

/** Collects field reasons that lead with the property name, as GoalDtos does (see validationPointer). */
private fun reasons(block: MutableList<String>.() -> Unit): ValidationResult {
    val list = mutableListOf<String>().apply(block)
    return if (list.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(list)
}

private fun MutableList<String>.uuid(name: String, raw: String?) {
    if (raw != null && runCatching { UUID.fromString(raw) }.isFailure) add("$name must be a UUID")
}

private fun MutableList<String>.text(name: String, raw: String?, max: Int, required: Boolean = false) {
    when {
        raw == null -> if (required) add("$name is required")
        required && raw.isBlank() -> add("$name can't be empty")
        raw.length > max -> add("$name must be at most $max characters")
    }
}

/** [lessonId] absent: the student's next upcoming CONFIRMED lesson with this teacher that has no plan yet. */
@Serializable
data class CreateLessonPlanRequest(
    val studentId: String? = null,
    val prepNote: String? = null,
    val lessonId: String? = null,
    val explainInRussian: Boolean = false,
) {
    fun validate() = reasons {
        if (studentId == null) add("studentId is required") else uuid("studentId", studentId)
        text("prepNote", prepNote, MAX_PREP_NOTE, required = true)
        uuid("lessonId", lessonId)
    }
}

/** A new [prepNote] / [explainInRussian] replaces the stored one; absent keeps it. */
@Serializable
data class RegenerateLessonPlanRequest(val prepNote: String? = null, val explainInRussian: Boolean? = null) {
    fun validate() = reasons { text("prepNote", prepNote, MAX_PREP_NOTE, required = prepNote != null) }
}

/**
 * Absent fields stay. [plan] replaces the whole plan. "" clears coveredNote and unlinks lessonId.
 * [status]: DRAFT | READY (DONE only by wrapping the lesson up).
 */
@Serializable
data class LessonPlanPatch(
    val prepNote: String? = null,
    val coveredNote: String? = null,
    val lessonId: String? = null,
    val status: String? = null,
    val plan: LessonPlanContent? = null,
) {
    fun validate() = reasons {
        text("prepNote", prepNote, MAX_PREP_NOTE, required = prepNote != null)
        text("coveredNote", coveredNote, MAX_COVERED_NOTE)
        if (!lessonId.isNullOrEmpty()) uuid("lessonId", lessonId)
        if (status != null && status !in listOf(LessonPlanStatus.DRAFT.name, LessonPlanStatus.READY.name))
            add("status must be DRAFT or READY")
        // Nested problems lead with their JSON pointer, which becomes errors[].pointer.
        plan?.let { content -> (LessonPlanRules.shape(content) + LessonPlanRules.uuidIssues(content)).forEach { add("/plan${it.pointer} ${it.message}") } }
    }
}

/** Idempotent: the same body twice gives the same result. */
@Serializable
data class WrapUpRequest(val coveredNote: String? = null, val itemStates: List<ItemStateChange> = emptyList()) {
    fun validate() = reasons {
        text("coveredNote", coveredNote, MAX_COVERED_NOTE)
        if (itemStates.isNotEmpty()) {
            val batch = BatchItemStatesRequest(items = itemStates).validate()
            if (batch is ValidationResult.Invalid) batch.reasons.forEach { add("itemStates: $it") }
        }
    }
}

// ---- Responses ----

@Serializable
data class PlanLessonRef(
    val id: String,
    val title: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val durationMinutes: Int,
    val status: LessonStatus,
)

@Serializable
data class PlanRoadmapItem(val id: String, val title: String, val kind: RoadmapItemKind, val state: RoadmapItemState)

@Serializable
data class LessonPlanResponse(
    val id: String,
    val studentId: String,
    val teacherId: String,
    val goalId: String? = null,
    val lesson: PlanLessonRef? = null,
    val prepNote: String,
    val explainInRussian: Boolean,
    /** Null until the first generation succeeded. */
    val plan: LessonPlanContent? = null,
    /** The plan's roadmapItemIds resolved (items deleted since are left out), for the wrap-up checklist. */
    val roadmapItems: List<PlanRoadmapItem> = emptyList(),
    val coveredNote: String? = null,
    val status: LessonPlanStatus,
    /** The latest LESSON_PREP job of the plan. */
    val job: GenerationJobResponse? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

/** [suggestedHomework]: hand its prompt to POST /lessons/{lessonId}/draft-bundles (kinds HOMEWORK). */
@Serializable
data class WrapUpResponse(val plan: LessonPlanResponse, val suggestedHomework: SuggestedHomework? = null)

@Serializable
data class NextLessonPrepResponse(val lesson: PlanLessonRef? = null, val plan: LessonPlanResponse? = null)

@Serializable
data class HistoryPlanRef(val id: String, val lessonGoal: String? = null, val status: LessonPlanStatus)

@Serializable
data class HistoryItem(val id: String, val title: String, val state: RoadmapItemState)

@Serializable
data class HistoryHomework(val id: String, val assignmentId: String, val title: String, val status: HomeworkStatus)

@Serializable
data class LessonHistoryEntry(
    val lessonId: String,
    val title: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val status: LessonStatus,
    val durationMinutes: Int,
    val rawNotes: String? = null,
    val plan: HistoryPlanRef? = null,
    val coveredNote: String? = null,
    /** Roadmap items last checked in this lesson. */
    val roadmapItems: List<HistoryItem> = emptyList(),
    /** The student's homework from assignments of this lesson. */
    val homework: List<HistoryHomework> = emptyList(),
)

/** [nextLesson]: the upcoming CONFIRMED lesson (on every page); [items]: past lessons, newest first. */
@Serializable
data class LessonHistoryResponse(
    val nextLesson: LessonHistoryEntry? = null,
    val items: List<LessonHistoryEntry>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

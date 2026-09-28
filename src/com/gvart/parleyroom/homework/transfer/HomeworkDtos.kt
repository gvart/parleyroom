package com.gvart.parleyroom.homework.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.document.transfer.DocumentVocabEntry
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkOutcome
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.service.UnitCheck
import com.gvart.parleyroom.material.data.MaterialType
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

// ---- Requests ----

@Serializable
data class CreateAssignmentRequest(
    val title: String,
    val instructions: String? = null,
    val dueDate: String? = null,
    val lessonId: String? = null,
    val studentIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val items: List<AssignmentItemInput>,
) {
    fun validate(): ValidationResult = when {
        title.isBlank() || title.length > 255 -> ValidationResult.Invalid("title must be 1..255 characters")
        (instructions?.length ?: 0) > MAX_INSTRUCTIONS -> ValidationResult.Invalid("instructions longer than $MAX_INSTRUCTIONS characters")
        items.isEmpty() || items.size > MAX_ITEMS -> ValidationResult.Invalid("items must have 1..$MAX_ITEMS entries")
        else -> ValidationResult.Valid
    }

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_INSTRUCTIONS = 10_000
    }
}

@Serializable
data class AssignmentItemInput(
    val kind: AssignmentItemKind,
    val documentId: String? = null,
    val materialId: String? = null,
    val title: String? = null,
    val task: String? = null,
    val responseType: HomeworkResponseType? = null,
)

@Serializable
data class UpdateAssignmentRequest(
    val title: String? = null,
    val instructions: String? = null,
    val dueDate: String? = null,
    val clearDueDate: Boolean = false,
) {
    fun validate(): ValidationResult = when {
        title != null && (title.isBlank() || title.length > 255) -> ValidationResult.Invalid("title must be 1..255 characters")
        (instructions?.length ?: 0) > CreateAssignmentRequest.MAX_INSTRUCTIONS ->
            ValidationResult.Invalid("instructions longer than ${CreateAssignmentRequest.MAX_INSTRUCTIONS} characters")
        else -> ValidationResult.Valid
    }
}

@Serializable
data class SaveAnswersRequest(
    val answers: List<AnswerInput>,
) {
    fun validate(): ValidationResult =
        if (answers.size > 500) ValidationResult.Invalid("at most 500 answers per request") else ValidationResult.Valid
}

/** `answer: null` (or absent) removes the unit's answer. */
@Serializable
data class AnswerInput(
    val assignmentItemId: String,
    val blockId: String? = null,
    val itemId: String? = null,
    val answer: JsonObject? = null,
)

@Serializable
data class ReviewUnitInput(
    val assignmentItemId: String,
    val blockId: String? = null,
    val itemId: String? = null,
    val correct: Boolean? = null,
    val comment: String? = null,
)

/** PUT …/review: autosave of the teacher's review, no status change. */
@Serializable
data class ReviewDraftRequest(
    val feedback: String? = null,
    val units: List<ReviewUnitInput> = emptyList(),
) {
    fun validate(): ValidationResult = validateReview(feedback, units)
}

/** POST …/review: the review plus its outcome. */
@Serializable
data class ReviewRequest(
    val feedback: String? = null,
    val units: List<ReviewUnitInput> = emptyList(),
    val outcome: HomeworkOutcome,
) {
    fun validate(): ValidationResult = validateReview(feedback, units)
}

private fun validateReview(feedback: String?, units: List<ReviewUnitInput>): ValidationResult = when {
    (feedback?.length ?: 0) > 10_000 -> ValidationResult.Invalid("feedback longer than 10000 characters")
    units.any { (it.comment?.length ?: 0) > 5_000 } -> ValidationResult.Invalid("comment longer than 5000 characters")
    else -> ValidationResult.Valid
}

// ---- Responses ----

@Serializable
data class PersonRef(val id: String, val firstName: String, val lastName: String)

@Serializable
data class HomeworkScore(val closedCorrect: Int, val closedTotal: Int, val pendingReview: Int, val unanswered: Int)

@Serializable
data class ItemMaterial(
    val id: String,
    val name: String,
    val type: MaterialType,
    val contentType: String?,
    val downloadUrl: String?,
)

@Serializable
data class AssignmentItemResponse(
    val id: String,
    val position: Int,
    val kind: AssignmentItemKind,
    val title: String,
    val task: String?,
    val responseType: HomeworkResponseType?,
    val documentId: String?,
    val documentRevision: Int?,
    val blocks: JsonArray?,
    val vocab: List<DocumentVocabEntry>?,
    val materialId: String?,
    val material: ItemMaterial?,
)

@Serializable
data class AssignmentResponse(
    val id: String,
    val teacherId: String,
    val title: String,
    val instructions: String?,
    val dueDate: String?,
    val lessonId: String?,
    val groupIds: List<String>,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    val items: List<AssignmentItemResponse>,
    val homework: List<HomeworkSummary>,
)

@Serializable
data class AssignmentSummary(
    val id: String,
    val teacherId: String,
    val title: String,
    val instructions: String?,
    val dueDate: String?,
    val lessonId: String?,
    val groupIds: List<String>,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    val itemCount: Int,
    val studentCount: Int,
    val statusCounts: Map<HomeworkStatus, Int>,
)

@Serializable
data class AssignmentPageResponse(
    val assignments: List<AssignmentSummary>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

@Serializable
data class HomeworkSummary(
    val id: String,
    val assignmentId: String,
    val title: String,
    val dueDate: String?,
    val lessonId: String?,
    val status: HomeworkStatus,
    val lastOutcome: HomeworkOutcome?,
    val attempt: Int,
    val itemCount: Int,
    val student: PersonRef,
    val teacher: PersonRef,
    val answeredUnits: Int,
    val totalUnits: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastSavedAt: OffsetDateTime?,
    val summary: HomeworkScore?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val submittedAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val reviewedAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val returnedAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val doneAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class HomeworkPageResponse(
    val homework: List<HomeworkSummary>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

@Serializable
data class UnitResponse(
    val assignmentItemId: String,
    val blockId: String?,
    val itemId: String?,
    val blockType: String?,
    val questionKind: String?,
    val check: UnitCheck,
    val answer: JsonObject?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val answeredAt: OffsetDateTime?,
    val autoResult: AutoResult?,
    val autoScore: Double?,
    val caseMismatch: Boolean?,
    val gapResults: List<String>?,
    val teacherCorrect: Boolean?,
    val correct: Boolean?,
    val comment: String?,
)

@Serializable
data class HomeworkUploadResponse(
    val id: String,
    val assignmentItemId: String,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val downloadUrl: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

/** Full homework: [HomeworkSummary] fields plus content, answers and review. */
@Serializable
data class HomeworkResponse(
    val id: String,
    val assignmentId: String,
    val title: String,
    val dueDate: String?,
    val lessonId: String?,
    val status: HomeworkStatus,
    val lastOutcome: HomeworkOutcome?,
    val attempt: Int,
    val itemCount: Int,
    val student: PersonRef,
    val teacher: PersonRef,
    val answeredUnits: Int,
    val totalUnits: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastSavedAt: OffsetDateTime?,
    val summary: HomeworkScore?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val submittedAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val reviewedAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val returnedAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val doneAt: OffsetDateTime?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    val instructions: String?,
    val feedback: String?,
    val items: List<AssignmentItemResponse>,
    val units: List<UnitResponse>,
    val uploads: List<HomeworkUploadResponse>,
)

@Serializable
data class AnswersSavedResponse(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastSavedAt: OffsetDateTime?,
    val answeredUnits: Int,
    val totalUnits: Int,
)

/** Teacher/admin fill the first three, students the last three; the others are null. */
@Serializable
data class HomeworkCountsResponse(
    val toReview: Int? = null,
    val overdue: Int? = null,
    val openTotal: Int? = null,
    val open: Int? = null,
    val dueSoon: Int? = null,
    val returned: Int? = null,
)

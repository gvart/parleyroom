package com.gvart.parleyroom.ai.transfer

import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** What a job works on, so the task tray can name it and link to its review page. */
enum class AiJobTargetType { LESSON, STUDENT, DOCUMENT, MATERIAL, VOCAB }

/** One row of the teacher's AI task tray (GET /api/v1/ai/jobs): no input or result payloads. */
@Serializable
data class AiJobSummary(
    val id: String,
    val kind: GenerationJobKind,
    val status: GenerationJobStatus,
    val targetType: AiJobTargetType,
    val bundleId: String? = null,
    val lessonId: String? = null,
    val lessonTitle: String? = null,
    /** The student of a student-scope draft or of a 1:1 lesson. */
    val studentId: String? = null,
    val studentName: String? = null,
    /** The club of a group lesson. */
    val groupName: String? = null,
    val documentId: String? = null,
    val documentTitle: String? = null,
    val materialId: String? = null,
    val materialName: String? = null,
    /** FILL_TRANSLATIONS: how many words get proposals. */
    val entryCount: Int? = null,
    /** GENERATE / REFINE of a draft: the parts worked on. */
    val draftKinds: List<DraftKind>? = null,
    /** REFINE of a single draft item rather than the whole draft. */
    val itemRefine: Boolean = false,
    val errorCode: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startedAt: OffsetDateTime? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val finishedAt: OffsetDateTime? = null,
)

/**
 * Pushed on the notification stream (not stored as a notification) when a job is queued or finishes.
 * [type] is AI_JOB_STARTED or AI_JOB_FINISHED.
 */
@Serializable
data class AiJobEvent(val type: String, val job: AiJobSummary) {
    companion object {
        const val STARTED = "AI_JOB_STARTED"
        const val FINISHED = "AI_JOB_FINISHED"
    }
}

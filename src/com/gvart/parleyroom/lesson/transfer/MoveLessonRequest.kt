package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.availability.service.ScheduleWarning
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class MoveLessonRequest(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    /** Null keeps the current duration. */
    val durationMinutes: Int? = null,
    /** Null keeps the current topic. */
    val topic: String? = null,
    /** Send a LESSON_MOVED notification to the students. */
    val notify: Boolean = true,
    /** Check for conflicts and warnings without changing anything. */
    val dryRun: Boolean = false,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (scheduledAt.isBefore(OffsetDateTime.now())) add("Scheduled time must be in the future")
            durationMinutes?.let { if (it <= 0) add("Duration must be positive") }
            topic?.let { if (it.isBlank()) add("Topic can't be empty") }
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

@Serializable
data class MoveLessonResponse(
    /** The lesson after the move, or unchanged on a dry run. */
    val lesson: LessonResponse,
    /** Soft issues with the new time; the move is not blocked by them. */
    val warnings: List<ScheduleWarning>,
    val dryRun: Boolean,
)

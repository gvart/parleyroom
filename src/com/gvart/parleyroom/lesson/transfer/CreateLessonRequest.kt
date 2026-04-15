package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class CreateLessonRequest(
    val teacherId: String,
    val studentIds: List<String>,
    val title: String,
    val type: LessonType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val durationMinutes: Int = 60,
    val topic: String,
    val level: LanguageLevel? = null,
    val maxParticipants: Int? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (teacherId.isBlank()) add("Teacher ID can't be empty")
            if (studentIds.isEmpty()) add("At least one student is required")
            if (title.isBlank()) add("Title can't be empty")
            if (scheduledAt.isBefore(OffsetDateTime.now())) add("Scheduled time must be in the future")
            if (topic.isBlank()) add("Topic can't be empty")
            if (durationMinutes <= 0) add("Duration must be positive")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

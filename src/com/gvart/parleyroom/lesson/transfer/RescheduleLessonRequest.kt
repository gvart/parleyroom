package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class RescheduleLessonRequest(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val newScheduledAt: OffsetDateTime,
    val note: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (newScheduledAt.isBefore(OffsetDateTime.now())) add("New scheduled time must be in the future")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

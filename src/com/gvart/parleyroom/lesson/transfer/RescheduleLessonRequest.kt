package com.gvart.parleyroom.lesson.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class RescheduleLessonRequest(
    val newScheduledAt: String,
    val note: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (newScheduledAt.isBlank()) add("New scheduled time can't be empty")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}
package com.gvart.parleyroom.goal.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class CreateGoalRequest(
    val studentId: String,
    val description: String,
    val targetDate: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (studentId.isBlank()) add("Student ID can't be empty")
            if (description.isBlank()) add("Description can't be empty")
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

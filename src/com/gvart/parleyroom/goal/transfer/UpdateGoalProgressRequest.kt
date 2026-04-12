package com.gvart.parleyroom.goal.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class UpdateGoalProgressRequest(
    val progress: Int,
) {
    fun validate(): ValidationResult {
        if (progress < 0 || progress > 100)
            return ValidationResult.Invalid("Progress must be between 0 and 100")
        return ValidationResult.Valid
    }
}

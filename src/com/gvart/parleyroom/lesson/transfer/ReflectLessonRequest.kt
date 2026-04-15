package com.gvart.parleyroom.lesson.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class ReflectLessonRequest(
    val studentReflection: String? = null,
    val studentHardToday: String? = null,
) {
    fun validate(): ValidationResult {
        if (studentReflection.isNullOrBlank() && studentHardToday.isNullOrBlank())
            return ValidationResult.Invalid(listOf("At least one of studentReflection or studentHardToday must be provided"))
        return ValidationResult.Valid
    }
}

package com.gvart.parleyroom.homework.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class SubmitHomeworkRequest(
    val submissionText: String? = null,
    val submissionUrl: String? = null,
) {
    fun validate(): ValidationResult {
        if (submissionText.isNullOrBlank() && submissionUrl.isNullOrBlank())
            return ValidationResult.Invalid(listOf("At least one of submissionText or submissionUrl must be provided"))
        return ValidationResult.Valid
    }
}

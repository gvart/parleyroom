package com.gvart.parleyroom.homework.transfer

import com.gvart.parleyroom.homework.data.HomeworkStatus
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class ReviewHomeworkRequest(
    val status: HomeworkStatus,
    val teacherFeedback: String? = null,
) {
    fun validate(): ValidationResult {
        if (status != HomeworkStatus.DONE && status != HomeworkStatus.REJECTED)
            return ValidationResult.Invalid("Review status must be DONE or REJECTED")
        return ValidationResult.Valid
    }
}

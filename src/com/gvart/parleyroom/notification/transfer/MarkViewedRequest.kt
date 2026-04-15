package com.gvart.parleyroom.notification.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class MarkViewedRequest(
    val notificationIds: List<String>,
) {
    fun validate(): ValidationResult {
        if (notificationIds.isEmpty())
            return ValidationResult.Invalid(listOf("notificationIds can't be empty"))
        return ValidationResult.Valid
    }
}

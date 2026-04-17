package com.gvart.parleyroom.admin.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class AdminSetPasswordRequest(
    val newPassword: String,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (newPassword.length < 8) add("Password must be at least 8 characters")
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

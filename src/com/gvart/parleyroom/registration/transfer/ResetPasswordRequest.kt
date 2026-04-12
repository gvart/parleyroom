package com.gvart.parleyroom.registration.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class ResetPasswordRequest(
    val token: String,
    val newPassword: String,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (token.isBlank()) add("Token can't be empty")
            if (newPassword.isBlank()) add("New password can't be empty")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}
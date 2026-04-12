package com.gvart.parleyroom.user.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class AuthenticateRequest(
    val email: String,
    val password: String
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (email.isBlank()) add("Email can't be empty")
            if (password.isBlank()) add("Password can't be empty")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}
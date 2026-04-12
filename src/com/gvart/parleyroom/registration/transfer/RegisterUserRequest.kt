package com.gvart.parleyroom.registration.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class RegisterUserRequest(
    val token: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val password: String,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (token.isBlank()) add("Token can't be empty")
            if (email.isBlank()) add("Email can't be empty")
            if (firstName.isBlank()) add("First name can't be empty")
            if (lastName.isBlank()) add("Last name can't be empty")
            if (password.isBlank()) add("Password can't be empty")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

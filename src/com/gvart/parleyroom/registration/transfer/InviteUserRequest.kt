package com.gvart.parleyroom.registration.transfer

import com.gvart.parleyroom.user.data.UserRole
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class InviteUserRequest(
    val email: String,
    val role: UserRole,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (email.isBlank()) add("Email can't be empty")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}
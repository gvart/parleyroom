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
            else if (!isValidEmail(email)) add("Email format is invalid")
        }

        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }

    private fun isValidEmail(email: String): Boolean {
        val parts = email.split("@")
        if (parts.size != 2) return false
        val (local, domain) = parts
        return local.isNotEmpty() && domain.contains(".") && domain.length >= 3
    }
}
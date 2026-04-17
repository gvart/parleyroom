package com.gvart.parleyroom.admin.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.user.data.UserRole
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class AdminCreateUserRequest(
    val email: String,
    val firstName: String,
    val lastName: String,
    val role: UserRole,
    val password: String,
    val level: LanguageLevel? = null,
    val locale: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (email.isBlank()) add("Email can't be empty")
            else if (!isValidEmail(email)) add("Email format is invalid")
            if (firstName.isBlank()) add("First name can't be empty")
            if (lastName.isBlank()) add("Last name can't be empty")
            if (password.length < 8) add("Password must be at least 8 characters")
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

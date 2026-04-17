package com.gvart.parleyroom.admin.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class AdminUpdateUserRequest(
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val role: UserRole? = null,
    val status: UserStatus? = null,
    val level: LanguageLevel? = null,
    val locale: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            val anyProvided = listOf(email, firstName, lastName, role, status, level, locale).any { it != null }
            if (!anyProvided) add("At least one field must be provided")
            if (email != null) {
                if (email.isBlank()) add("Email can't be empty")
                else if (!isValidEmail(email)) add("Email format is invalid")
            }
            if (firstName != null && firstName.isBlank()) add("First name can't be empty")
            if (lastName != null && lastName.isBlank()) add("Last name can't be empty")
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

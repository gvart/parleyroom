package com.gvart.parleyroom.registration.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.user.data.UserRole
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.util.UUID

const val MAX_INVITE_MATERIALS = 5

@Serializable
data class InviteUserRequest(
    val email: String,
    val role: UserRole,
    /** Student invites only: ru | uk | en (default ru). Ignored for other roles. */
    val nativeLanguage: String? = null,
    /** Student invites by a teacher only: the teacher's own (non-LINK) materials, shared with the student on signup. */
    val materialIds: List<String> = emptyList(),
    /** Student invites only: copied to the student's level on signup. */
    val level: LanguageLevel? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (email.isBlank()) add("Email can't be empty")
            else if (!isValidEmail(email)) add("Email format is invalid")
            if (materialIds.size > MAX_INVITE_MATERIALS) add("At most $MAX_INVITE_MATERIALS materials can be attached")
            if (materialIds.any { runCatching { UUID.fromString(it) }.isFailure }) add("materialIds must be valid UUIDs")
            if (role != UserRole.STUDENT && materialIds.isNotEmpty()) add("materialIds are only allowed for student invites")
            if (role != UserRole.STUDENT && level != null) add("level is only allowed for student invites")
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

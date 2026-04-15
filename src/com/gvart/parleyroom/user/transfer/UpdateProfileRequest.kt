package com.gvart.parleyroom.user.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val locale: String? = null,
    val level: LanguageLevel? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (firstName == null && lastName == null && locale == null && level == null) {
                add("At least one field must be provided")
            }
            if (firstName != null && firstName.trim().isEmpty()) add("First name can't be blank")
            if (lastName != null && lastName.trim().isEmpty()) add("Last name can't be blank")
            if (locale != null && (locale.length < 2 || locale.length > 5)) {
                add("Locale must be between 2 and 5 characters")
            }
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}

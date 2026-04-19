package com.gvart.parleyroom.user.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable
import java.time.ZoneId

@Serializable
data class UpdateProfileRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val locale: String? = null,
    val level: LanguageLevel? = null,
    val timezone: String? = null,
    val bookingBufferMinutes: Int? = null,
    val bookingMinNoticeHours: Int? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            val noField = firstName == null && lastName == null && locale == null && level == null
                    && timezone == null && bookingBufferMinutes == null && bookingMinNoticeHours == null
            if (noField) add("At least one field must be provided")
            if (firstName != null && firstName.trim().isEmpty()) add("First name can't be blank")
            if (lastName != null && lastName.trim().isEmpty()) add("Last name can't be blank")
            if (locale != null && (locale.length < 2 || locale.length > 5)) {
                add("Locale must be between 2 and 5 characters")
            }
            if (timezone != null) {
                val valid = runCatching { ZoneId.of(timezone) }.isSuccess
                if (!valid) add("Timezone '$timezone' is not a valid IANA zone")
            }
            if (bookingBufferMinutes != null && (bookingBufferMinutes < 0 || bookingBufferMinutes > 240)) {
                add("bookingBufferMinutes must be between 0 and 240")
            }
            if (bookingMinNoticeHours != null && (bookingMinNoticeHours < 0 || bookingMinNoticeHours > 168)) {
                add("bookingMinNoticeHours must be between 0 and 168")
            }
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}

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
    /** Students only (ru | uk | en); anyone else gets 400 NATIVE_LANGUAGE_STUDENTS_ONLY. */
    val nativeLanguage: String? = null,
    /** true: marks the interface language as chosen (sets localeConfirmedAt = now). */
    val confirmLocale: Boolean? = null,
    /** Not settable here (teachers: PUT /students/{id}/level, admins: PATCH /admin/users/{id}); always rejected. */
    val level: LanguageLevel? = null,
    val timezone: String? = null,
    val bookingBufferMinutes: Int? = null,
    val bookingMinNoticeHours: Int? = null,
    /** Teachers only; anyone else gets 400 APPROVAL_SETTINGS_TEACHERS_ONLY. */
    val autoConfirmBookings: Boolean? = null,
    /** Teachers only; anyone else gets 400 APPROVAL_SETTINGS_TEACHERS_ONLY. */
    val autoAcceptClubJoins: Boolean? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            val noField = firstName == null && lastName == null && locale == null && level == null
                    && nativeLanguage == null && confirmLocale != true && timezone == null && bookingBufferMinutes == null && bookingMinNoticeHours == null
                    && autoConfirmBookings == null && autoAcceptClubJoins == null
            if (noField) add("At least one field must be provided")
            if (level != null) add("level can't be changed here; it is set by the teacher")
            if (firstName != null && firstName.trim().isEmpty()) add("First name can't be blank")
            if (lastName != null && lastName.trim().isEmpty()) add("Last name can't be blank")
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

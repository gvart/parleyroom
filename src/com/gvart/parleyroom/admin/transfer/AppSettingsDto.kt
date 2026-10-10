package com.gvart.parleyroom.admin.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

/** Platform settings (GET and PUT /api/v1/admin/settings). */
@Serializable
data class AppSettingsDto(
    /** Days an invite link stays valid, 1..90. */
    val inviteExpiryDays: Int,
) {
    fun validate(): ValidationResult =
        if (inviteExpiryDays !in 1..90) ValidationResult.Invalid("inviteExpiryDays must be between 1 and 90")
        else ValidationResult.Valid
}

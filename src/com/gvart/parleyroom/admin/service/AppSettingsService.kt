package com.gvart.parleyroom.admin.service

import com.gvart.parleyroom.admin.data.AppSettingsTable
import com.gvart.parleyroom.admin.transfer.AppSettingsDto
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime

/** The app_settings row; defaults apply when it is missing. */
class AppSettingsService {

    fun settings(): AppSettingsDto = transaction {
        val row = AppSettingsTable.selectAll().singleOrNull()
        AppSettingsDto(
            inviteExpiryDays = row?.get(AppSettingsTable.inviteExpiryDays) ?: AppSettingsTable.DEFAULT_INVITE_EXPIRY_DAYS,
        )
    }

    /** Days a new invite link stays valid. */
    fun inviteExpiryDays(): Int = settings().inviteExpiryDays

    fun update(request: AppSettingsDto, principal: UserPrincipal): AppSettingsDto {
        transaction {
            AppSettingsTable.upsert {
                it[id] = AppSettingsTable.ROW_ID
                it[inviteExpiryDays] = request.inviteExpiryDays
                it[updatedAt] = OffsetDateTime.now()
                it[updatedBy] = principal.id
            }
        }
        return settings()
    }
}

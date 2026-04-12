package com.gvart.parleyroom.registration.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object PasswordResetTable : UUIDTable("password_resets") {
    val userId = reference("user_id", UserTable)
    val token = uuid("token").uniqueIndex()
    val used = bool("used").default(false)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
}

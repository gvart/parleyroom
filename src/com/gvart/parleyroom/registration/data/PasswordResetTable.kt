package com.gvart.parleyroom.registration.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object PasswordResetTable : UUIDTable("password_resets") {
    val userId = reference("user_id", UserTable)
    val token = javaUUID("token").uniqueIndex()
    val used = bool("used").default(false)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
}

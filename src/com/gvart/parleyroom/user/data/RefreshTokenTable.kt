package com.gvart.parleyroom.user.data

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object RefreshTokenTable : Table("refresh_tokens") {
    val userId = reference("user_id", UserTable)
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(userId)
}
package com.gvart.parleyroom.common.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object IdempotencyKeyTable : Table("idempotency_keys") {
    val userId = reference("user_id", UserTable)
    val key = varchar("idempotency_key", 255)
    val method = varchar("method", 10)
    val path = varchar("path", 500)
    val requestHash = char("request_hash", 64)
    val responseStatus = integer("response_status").nullable()
    val responseBody = text("response_body").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")

    override val primaryKey = PrimaryKey(userId, key)
}

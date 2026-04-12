package com.gvart.parleyroom.user.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class UserStatus { ACTIVE, REQUEST, INACTIVE }

object UserTable : UUIDTable("users") {
    val email = varchar("email", 255).uniqueIndex()
    val firstName = varchar("first_name", 255)
    val lastName = varchar("last_name", 255)
    val role = pgEnum<UserRole>("role", "USER_ROLE")
    val passwordHash = varchar("password_hash", 255)
    val avatarUrl = text("avatar_url").nullable()
    val initials = varchar("initials", 4)
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val points = integer("points").default(0)
    val status = pgEnum<UserStatus>("status", "USER_STATUS").default(UserStatus.ACTIVE)
    val locale = varchar("locale", 5).default("en")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}
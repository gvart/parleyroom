package com.gvart.parleyroom.user.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

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
    val status = pgEnum<UserStatus>("status", "USER_STATUS").default(UserStatus.ACTIVE)
    val locale = varchar("locale", 5).default(DEFAULT_LOCALE)
    /** Null until the user confirmed the interface language once (the clients' first-run picker). */
    val localeConfirmedAt = timestampWithTimeZone("locale_confirmed_at").nullable()
    /** Students only (ru | uk | en): the language of their translations and explanations. */
    val nativeLanguage = varchar("native_language", 5).nullable()
    val timezone = varchar("timezone", 64).default("Europe/Berlin")
    val bookingBufferMinutes = integer("booking_buffer_minutes").nullable()
    val bookingMinNoticeHours = integer("booking_min_notice_hours").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val failedLoginAttempts = integer("failed_login_attempts").default(0)
    val lockedUntil = timestampWithTimeZone("locked_until").nullable()
    val telegramId = long("telegram_id").nullable()
    val telegramUsername = varchar("telegram_username", 64).nullable()
}

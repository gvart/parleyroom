package com.gvart.parleyroom.registration.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object RegistrationTable : UUIDTable("registrations") {
    val email = varchar("email", 255)
    val token = varchar("token", 255)
    val invitedBy = reference("invited_by", UserTable).nullable()
    val used = bool("used").default(false)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
    val role = pgEnum<UserRole>("role", "USER_ROLE")
    val nativeLanguage = varchar("native_language", 5).nullable()
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    /** The user created from this invite, set on signup. */
    val userId = reference("user_id", UserTable).nullable()
    /** When the invite email last went out successfully; null if it never did. */
    val lastSentAt = timestampWithTimeZone("last_sent_at").nullable()
}

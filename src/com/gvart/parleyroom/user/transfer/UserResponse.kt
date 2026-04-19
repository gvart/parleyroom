package com.gvart.parleyroom.user.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val initials: String,
    val role: UserRole,
    val avatarUrl: String?,
    val level: LanguageLevel?,
    val status: UserStatus,
    val locale: String,
    val timezone: String,
    val bookingBufferMinutes: Int?,
    val bookingMinNoticeHours: Int?,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    val telegramId: Long? = null,
    val telegramUsername: String? = null,
)

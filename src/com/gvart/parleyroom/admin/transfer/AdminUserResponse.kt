package com.gvart.parleyroom.admin.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class AdminUserResponse(
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
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    val failedLoginAttempts: Int,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lockedUntil: OffsetDateTime?,
)

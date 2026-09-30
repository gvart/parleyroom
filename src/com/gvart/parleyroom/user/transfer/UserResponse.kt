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
    /** Students: ru | uk | en (translation language); teachers/admins: usually null. */
    val nativeLanguage: String? = null,
    /** Null: the client shows the one-time language picker. */
    @Serializable(with = OffsetDateTimeSerializer::class)
    val localeConfirmedAt: OffsetDateTime? = null,
    val timezone: String,
    val bookingBufferMinutes: Int?,
    val bookingMinNoticeHours: Int?,
    /** Teachers: students' valid 1:1 bookings are confirmed straight away. */
    val autoConfirmBookings: Boolean = false,
    /** Teachers: club joins with a free spot are accepted straight away. */
    val autoAcceptClubJoins: Boolean = false,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    val telegramId: Long? = null,
    val telegramUsername: String? = null,
)

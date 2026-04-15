package com.gvart.parleyroom.user.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class UserListResponse(
    val users: List<User>,
    val total: Long = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
) {
    @Serializable
    data class User(
        val id: String,
        val firstName: String,
        val lastName: String,
        val initials: String,
        val role: UserRole,
        val avatarUrl: String?,
        val level: LanguageLevel?,
        val status: UserStatus,
        @Serializable(with = OffsetDateTimeSerializer::class)
        val createdAt: OffsetDateTime,
        val locale: String,
    )
}

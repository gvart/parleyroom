package com.gvart.parleyroom.user.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import kotlinx.serialization.Serializable

@Serializable
data class UserListResponse(
    val users: List<User>
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
        val createdAt: String,
        val locale: String,
    )
}
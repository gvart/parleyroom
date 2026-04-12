package com.gvart.parleyroom.user.security

import com.gvart.parleyroom.user.data.UserRole
import java.util.UUID

data class UserPrincipal(
    val id: UUID,
    val email: String,
    val role: UserRole,
)

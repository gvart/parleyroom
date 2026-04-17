package com.gvart.parleyroom.admin.transfer

import com.gvart.parleyroom.user.data.UserStatus
import kotlinx.serialization.Serializable

@Serializable
data class AdminSetStatusRequest(
    val status: UserStatus,
)

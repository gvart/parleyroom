package com.gvart.parleyroom.admin.transfer

import kotlinx.serialization.Serializable

@Serializable
data class AdminUserListResponse(
    val users: List<AdminUserResponse>,
    val total: Long = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
)

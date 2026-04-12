package com.gvart.parleyroom.notification.transfer

import kotlinx.serialization.Serializable

@Serializable
data class NotificationPageResponse(
    val notifications: List<NotificationResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

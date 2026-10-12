package com.gvart.parleyroom.notification.transfer

import kotlinx.serialization.Serializable

@Serializable
data class NotificationPageResponse(
    val notifications: List<NotificationResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
    /** All of the user's unread notifications, whatever the page or filter. */
    val unreadCount: Long,
)

@Serializable
data class MarkAllViewedResponse(
    /** How many notifications this call marked viewed (0 when everything was read already). */
    val updated: Int,
)

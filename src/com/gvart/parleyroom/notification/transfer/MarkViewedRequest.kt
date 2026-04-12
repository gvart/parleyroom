package com.gvart.parleyroom.notification.transfer

import kotlinx.serialization.Serializable

@Serializable
data class MarkViewedRequest(
    val notificationIds: List<String>,
)

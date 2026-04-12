package com.gvart.parleyroom.notification.transfer

import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.user.data.UserRole
import kotlinx.serialization.Serializable

@Serializable
data class NotificationResponse(
    val id: String,
    val type: NotificationType,
    val referenceId: String? = null,
    val viewed: Boolean,
    val actor: NotificationActorResponse,
    val createdAt: String,
)

@Serializable
data class NotificationActorResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val role: UserRole,
)

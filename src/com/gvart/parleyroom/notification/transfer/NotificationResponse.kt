package com.gvart.parleyroom.notification.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.user.data.UserRole
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class NotificationResponse(
    val id: String,
    val type: NotificationType,
    val referenceId: String? = null,
    val viewed: Boolean,
    val actor: NotificationActorResponse,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

@Serializable
data class NotificationActorResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val role: UserRole,
)

package com.gvart.parleyroom.notification.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class NotificationType {
    LESSON_CREATED,
    LESSON_REQUESTED,
    LESSON_ACCEPTED,
    LESSON_CANCELLED,
    RESCHEDULE_REQUESTED,
    RESCHEDULE_ACCEPTED,
    RESCHEDULE_REJECTED,
    JOIN_REQUESTED,
    JOIN_ACCEPTED,
    JOIN_REJECTED,
    LESSON_STARTED,
    LESSON_COMPLETED,
}

object NotificationTable : UUIDTable("notifications") {
    val userId = reference("user_id", UserTable)
    val actorId = reference("actor_id", UserTable)
    val type = pgEnum<NotificationType>("type", "NOTIFICATION_TYPE")
    val referenceId = uuid("reference_id").nullable()
    val viewed = bool("viewed").default(false)
    val createdAt = timestampWithTimeZone("created_at")
}

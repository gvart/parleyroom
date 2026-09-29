package com.gvart.parleyroom.notification.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

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
    LESSON_MOVED,
    VOCAB_REVIEW_DUE,
    MATERIAL_SHARED,
    FOLDER_SHARED,
    MATERIAL_ATTACHED_TO_LESSON,
    HOMEWORK_ASSIGNED,
    HOMEWORK_SUBMITTED,
    HOMEWORK_REVIEWED,
    HOMEWORK_RETURNED,
}

object NotificationTable : UUIDTable("notifications") {
    val userId = reference("user_id", UserTable)
    val actorId = reference("actor_id", UserTable)
    val type = pgEnum<NotificationType>("type", "NOTIFICATION_TYPE")
    val referenceId = javaUUID("reference_id").nullable()
    val viewed = bool("viewed").default(false)
    val createdAt = timestampWithTimeZone("created_at")
    val deliverAfter = timestampWithTimeZone("deliver_after").nullable()
    val oldScheduledAt = timestampWithTimeZone("old_scheduled_at").nullable()
    val newScheduledAt = timestampWithTimeZone("new_scheduled_at").nullable()
}

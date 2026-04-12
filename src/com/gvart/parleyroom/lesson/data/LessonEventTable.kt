package com.gvart.parleyroom.lesson.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class LessonEventType {
    STATUS_CHANGE,
    RESCHEDULE_REQUESTED,
    RESCHEDULE_ACCEPTED,
    RESCHEDULE_REJECTED,
    LESSON_STARTED,
    LESSON_COMPLETED,
    LESSON_CANCELLED,
}

object LessonEventTable : UUIDTable("lesson_events") {
    val lessonId = reference("lesson_id", LessonTable)
    val eventType = pgEnum<LessonEventType>("event_type", "LESSON_EVENT_TYPE")
    val actorId = reference("actor_id", UserTable)
    val oldStatus = pgEnum<LessonStatus>("old_status", "LESSON_STATUS").nullable()
    val newStatus = pgEnum<LessonStatus>("new_status", "LESSON_STATUS").nullable()
    val oldScheduledAt = timestampWithTimeZone("old_scheduled_at").nullable()
    val newScheduledAt = timestampWithTimeZone("new_scheduled_at").nullable()
    val resolved = bool("resolved").default(false)
    val note = text("note").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}
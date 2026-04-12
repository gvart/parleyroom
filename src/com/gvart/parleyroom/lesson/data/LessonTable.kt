package com.gvart.parleyroom.lesson.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class LessonStatus { CONFIRMED, REQUEST, CANCELLED, COMPLETED, IN_PROGRESS }

object LessonTable : UUIDTable("lessons") {
    val title = varchar("title", 255)
    val type = pgEnum<LessonType>("type", "LESSON_TYPE")
    val scheduledAt = timestampWithTimeZone("scheduled_at")
    val durationMinutes = integer("duration_minutes").default(60)
    val teacherId = reference("teacher_id", UserTable)
    val status = pgEnum<LessonStatus>("status", "LESSON_STATUS").default(LessonStatus.CONFIRMED)
    val topic = varchar("topic", 500)
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val maxParticipants = integer("max_participants").nullable()
    val hasAiSummary = bool("has_ai_summary").default(false)
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val createdBy = reference("created_by", UserTable)
    val updatedBy = reference("updated_by", UserTable).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

package com.gvart.parleyroom.lesson.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

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
    val groupId = reference("group_id", GroupTable).nullable()
    val vocabDisplayFields = array<String>("vocab_display_fields", VarCharColumnType(32)).nullable()
    val allowTranslationToggle = bool("allow_translation_toggle").nullable()
    val rawNotes = text("raw_notes").nullable()
    val promptUsed = text("prompt_used").nullable()
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val cancelReason = text("cancel_reason").nullable()
    val cancelledBy = reference("cancelled_by", UserTable).nullable()
    val cancelledAt = timestampWithTimeZone("cancelled_at").nullable()
    val createdBy = reference("created_by", UserTable)
    val updatedBy = reference("updated_by", UserTable).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

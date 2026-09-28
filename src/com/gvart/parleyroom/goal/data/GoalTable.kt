package com.gvart.parleyroom.goal.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class GoalType { EXAM, LEVEL }
enum class GoalStatus { ACTIVE, ACHIEVED, ARCHIVED }

/** Auto-tracked goals (brief §5.9): progress is computed from the student's progress, never stored. */
object GoalTable : UUIDTable("goals") {
    val studentId = reference("student_id", UserTable)
    val teacherId = reference("teacher_id", UserTable)
    val type = pgEnum<GoalType>("type", "GOAL_TYPE")
    val examName = varchar("exam_name", 100).nullable()
    val targetLevel = pgEnum<LanguageLevel>("target_level", "LANGUAGE_LEVEL")
    val targetDate = date("target_date").nullable()
    val note = text("note").nullable()
    /** Progress percent computed at creation; the start of the on-track line. */
    val baselinePercent = integer("baseline_percent").nullable()
    val status = pgEnum<GoalStatus>("status", "GOAL_STATUS").default(GoalStatus.ACTIVE)
    val statusChangedAt = timestampWithTimeZone("status_changed_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

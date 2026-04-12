package com.gvart.parleyroom.goal.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class GoalSetBy { TEACHER, STUDENT }
enum class GoalStatus { ACTIVE, COMPLETED, ABANDONED }

object LearningGoalTable : UUIDTable("learning_goals") {
    val studentId = reference("student_id", UserTable)
    val teacherId = reference("teacher_id", UserTable).nullable()
    val description = text("description")
    val progress = integer("progress").default(0)
    val setBy = pgEnum<GoalSetBy>("set_by", "GOAL_SET_BY")
    val targetDate = date("target_date").nullable()
    val status = pgEnum<GoalStatus>("status", "GOAL_STATUS").default(GoalStatus.ACTIVE)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

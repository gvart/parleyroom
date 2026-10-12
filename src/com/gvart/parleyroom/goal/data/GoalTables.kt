package com.gvart.parleyroom.goal.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class GoalStatus { ACTIVE, ACHIEVED, ARCHIVED }
enum class RoadmapItemKind { TOPIC, GRAMMAR, VOCAB, SKILL, EXAM }
enum class RoadmapItemState { NOT_STARTED, IN_PROGRESS, DONE }
enum class SelfAssessmentAnswer { YES, PARTLY, NOT_YET }

/** A student's goal (at most one ACTIVE per student). The teacher-input fields are never shown to students. */
object GoalTable : UUIDTable("goals") {
    val studentId = reference("student_id", UserTable)
    val teacherId = reference("teacher_id", UserTable).nullable()
    val title = varchar("title", 200)
    val exam = varchar("exam", 100).nullable()
    val targetLevel = pgEnum<LanguageLevel>("target_level", "LANGUAGE_LEVEL").nullable()
    val startDate = date("start_date")
    val deadline = date("deadline")
    val lessonsPerWeek = decimal("lessons_per_week", 3, 1)
    val lessonMinutes = integer("lesson_minutes")
    val levelNote = text("level_note").nullable()
    val interests = text("interests").nullable()
    val materials = text("materials").nullable()
    val status = pgEnum<GoalStatus>("status", "GOAL_STATUS").default(GoalStatus.ACTIVE)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object RoadmapMilestoneTable : UUIDTable("roadmap_milestones") {
    val goalId = reference("goal_id", GoalTable)
    val position = integer("position")
    val title = varchar("title", 200)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val weight = decimal("weight", 6, 2)
    val topic = text("topic").nullable()
    val grammarPoints = array<String>("grammar_points", TextColumnType())
    val wordFields = array<String>("word_fields", TextColumnType())
    val examPart = text("exam_part").nullable()
    val teacherTips = text("teacher_tips").nullable()
    /** Student-facing "Ich kann …" statement in simple German. */
    val canDo = text("can_do")
    val doneAt = timestampWithTimeZone("done_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object RoadmapItemTable : UUIDTable("roadmap_items") {
    val milestoneId = reference("milestone_id", RoadmapMilestoneTable)
    val position = integer("position")
    val kind = pgEnum<RoadmapItemKind>("kind", "ROADMAP_ITEM_KIND")
    val title = varchar("title", 300)
    val state = pgEnum<RoadmapItemState>("state", "ROADMAP_ITEM_STATE").default(RoadmapItemState.NOT_STARTED)
    val stateChangedAt = timestampWithTimeZone("state_changed_at").nullable()
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object MilestoneSelfAssessmentTable : UUIDTable("milestone_self_assessments") {
    val milestoneId = reference("milestone_id", RoadmapMilestoneTable)
    val studentId = reference("student_id", UserTable)
    val answer = pgEnum<SelfAssessmentAnswer>("answer", "SELF_ASSESSMENT_ANSWER")
    val comment = varchar("comment", 1000).nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

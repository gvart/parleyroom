package com.gvart.parleyroom.lessonprep.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanContent
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** DRAFT: generated or being edited; READY: the teacher is happy with it; DONE: the lesson was wrapped up. */
enum class LessonPlanStatus { DRAFT, READY, DONE }

/** A teacher's preparation of one lesson for one student. Never shown to students. */
object LessonPlanTable : UUIDTable("lesson_plans") {
    val studentId = reference("student_id", UserTable)
    val teacherId = reference("teacher_id", UserTable)
    val goalId = reference("goal_id", GoalTable).nullable()
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val prepNote = text("prep_note")
    val explainInRussian = bool("explain_in_russian").default(false)
    val plan = jsonb<LessonPlanContent>("plan", Json { ignoreUnknownKeys = true }).nullable()
    val coveredNote = text("covered_note").nullable()
    val status = pgEnum<LessonPlanStatus>("status", "LESSON_PLAN_STATUS").default(LessonPlanStatus.DRAFT)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

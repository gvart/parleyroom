package com.gvart.parleyroom.lesson.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.sql.Table

enum class LessonStudentStatus { CONFIRMED, REQUESTED, REJECTED }

object LessonStudentTable : Table("lesson_students") {
    val lessonId = reference("lesson_id", LessonTable)
    val studentId = reference("student_id", UserTable)
    val status = pgEnum<LessonStudentStatus>("status", "LESSON_STUDENT_STATUS")
        .default(LessonStudentStatus.CONFIRMED)
    val attended = bool("attended").default(false)

    override val primaryKey = PrimaryKey(lessonId, studentId)
}
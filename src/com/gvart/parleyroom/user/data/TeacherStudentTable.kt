package com.gvart.parleyroom.user.data

import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.data.pgEnum
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.EnumerationColumnType
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object TeacherStudentTable : UUIDTable("teacher_students") {
    val teacherId = reference("teacher_id", UserTable)
    val studentId = reference("student_id", UserTable)
    val lessonTypes = array("lesson_types", EnumerationColumnType(LessonType::class))
    val status = pgEnum<UserStatus>("status", "USER_STATUS").default(UserStatus.REQUEST)
    val startedAt = timestampWithTimeZone("started_at")

    init {
        uniqueIndex(teacherId, studentId)
    }
}
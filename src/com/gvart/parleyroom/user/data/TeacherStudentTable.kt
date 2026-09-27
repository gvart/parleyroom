package com.gvart.parleyroom.user.data

import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.data.pgEnum
import org.jetbrains.exposed.v1.core.EnumerationColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object TeacherStudentTable : UUIDTable("teacher_students") {
    val teacherId = reference("teacher_id", UserTable)
    val studentId = reference("student_id", UserTable)
    val lessonTypes = array("lesson_types", EnumerationColumnType(LessonType::class))
    val status = pgEnum<UserStatus>("status", "USER_STATUS").default(UserStatus.REQUEST)
    val startedAt = timestampWithTimeZone("started_at")
    val vocabDisplayFields = array<String>("vocab_display_fields", VarCharColumnType(32)).nullable()
    val allowTranslationToggle = bool("allow_translation_toggle").nullable()

    init {
        uniqueIndex(teacherId, studentId)
    }
}
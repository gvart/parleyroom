package com.gvart.parleyroom.material.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class MaterialType { PDF, AUDIO, VIDEO, LINK }

object MaterialTable : UUIDTable("materials") {
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val studentId = reference("student_id", UserTable).nullable()
    val teacherId = reference("teacher_id", UserTable)
    val name = varchar("name", 255)
    val type = pgEnum<MaterialType>("type", "MATERIAL_TYPE")
    val url = text("url")
    val contentType = varchar("content_type", 100).nullable()
    val fileSize = long("file_size").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

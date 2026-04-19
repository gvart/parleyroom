package com.gvart.parleyroom.material.data

import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object LessonMaterialTable : Table("lesson_materials") {
    val lessonId = reference("lesson_id", LessonTable)
    val materialId = reference("material_id", MaterialTable)
    val attachedBy = reference("attached_by", UserTable)
    val attachedAt = timestampWithTimeZone("attached_at")

    override val primaryKey = PrimaryKey(lessonId, materialId)
}

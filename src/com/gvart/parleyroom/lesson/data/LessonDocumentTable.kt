package com.gvart.parleyroom.lesson.data

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object LessonDocumentTable : UUIDTable("lesson_documents") {
    val lessonId = reference("lesson_id", LessonTable).uniqueIndex()
    val aiSummary = text("ai_summary").nullable()
    val sharedDocument = text("shared_document").nullable()
    val teacherNotes = text("teacher_notes").nullable()
    val studentNotes = text("student_notes").nullable()
    val teacherWentWell = text("teacher_went_well").nullable()
    val teacherWorkingOn = text("teacher_working_on").nullable()
    val studentReflection = text("student_reflection").nullable()
    val studentHardToday = text("student_hard_today").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}
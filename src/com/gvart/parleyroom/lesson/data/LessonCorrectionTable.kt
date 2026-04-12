package com.gvart.parleyroom.lesson.data

import org.jetbrains.exposed.dao.id.UUIDTable

object LessonCorrectionTable : UUIDTable("lesson_corrections") {
    val lessonDocumentId = reference("lesson_document_id", LessonDocumentTable)
    val incorrect = text("incorrect")
    val correct = text("correct")
    val orderIndex = integer("order_index").default(0)
}
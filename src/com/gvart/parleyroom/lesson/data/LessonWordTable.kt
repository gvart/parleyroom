package com.gvart.parleyroom.lesson.data

import org.jetbrains.exposed.dao.id.UUIDTable

object LessonWordTable : UUIDTable("lesson_words") {
    val lessonDocumentId = reference("lesson_document_id", LessonDocumentTable)
    val german = varchar("german", 255)
    val english = varchar("english", 255)
    val orderIndex = integer("order_index").default(0)
}
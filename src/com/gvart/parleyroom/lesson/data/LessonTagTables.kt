package com.gvart.parleyroom.lesson.data

import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object LessonTopicTable : Table("lesson_topics") {
    val lessonId = reference("lesson_id", LessonTable)
    val topicId = reference("topic_id", TopicTable)

    override val primaryKey = PrimaryKey(lessonId, topicId)
}

object LessonGrammarTopicTable : Table("lesson_grammar_topics") {
    val lessonId = reference("lesson_id", LessonTable)
    val grammarTopicId = reference("grammar_topic_id", GrammarTopicTable)

    override val primaryKey = PrimaryKey(lessonId, grammarTopicId)
}

/** Corrected sentences of a lesson. */
object LessonCorrectionTable : UUIDTable("lesson_corrections") {
    val lessonId = reference("lesson_id", LessonTable)
    val incorrect = text("incorrect")
    val correct = text("correct")
    val orderIndex = integer("order_index").default(0)
}

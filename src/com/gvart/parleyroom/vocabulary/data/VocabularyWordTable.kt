package com.gvart.parleyroom.vocabulary.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class VocabCategory { NOUN, VERB, ADJECTIVE, ADVERB, GRAMMAR }
enum class VocabStatus { NEW, REVIEW, LEARNED }

object VocabularyWordTable : UUIDTable("vocabulary_words") {
    val studentId = reference("student_id", UserTable)
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val german = varchar("german", 255)
    val english = varchar("english", 255)
    val exampleSentence = text("example_sentence").nullable()
    val exampleTranslation = text("example_translation").nullable()
    val category = pgEnum<VocabCategory>("category", "VOCAB_CATEGORY")
    val status = pgEnum<VocabStatus>("status", "VOCAB_STATUS").default(VocabStatus.NEW)
    val nextReviewAt = timestampWithTimeZone("next_review_at").nullable()
    val reviewCount = integer("review_count").default(0)
    val addedAt = timestampWithTimeZone("added_at")

    init {
        uniqueIndex(studentId, german)
    }
}

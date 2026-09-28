package com.gvart.parleyroom.practice.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.practice.transfer.SentenceFeedback
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** FSRS grade; [value] is the py-fsrs rating number. */
enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

enum class PracticeMode { DE_TO_MEANING, MEANING_TO_DE, ARTICLE }

object VocabReviewTable : UUIDTable("vocab_reviews") {
    val studentVocabId = reference("student_vocab_id", StudentVocabTable, onDelete = ReferenceOption.CASCADE)
    val studentId = reference("student_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val rating = pgEnum<Rating>("rating", "VOCAB_RATING")
    val mode = pgEnum<PracticeMode>("mode", "PRACTICE_MODE")
    /** FSRS state before the review; 0 = the card's first review. */
    val stateBefore = short("state_before")
    val responseMs = integer("response_ms").nullable()
    val reviewedAt = timestampWithTimeZone("reviewed_at")
}

object StudentVocabSentenceTable : UUIDTable("student_vocab_sentences") {
    val studentVocabId = reference("student_vocab_id", StudentVocabTable, onDelete = ReferenceOption.CASCADE)
    val studentId = reference("student_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val sentence = text("sentence")
    val feedback = jsonb<SentenceFeedback>("feedback", Json.Default)
    val modelId = varchar("model_id", 100).nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

package com.gvart.parleyroom.vocabulary.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class StudentVocabStatus { NEW, LEARNING, REVIEW, LEARNED }

object StudentVocabTable : UUIDTable("student_vocab") {
    val studentId = reference("student_id", UserTable)
    val vocabEntryId = reference("vocab_entry_id", VocabEntryTable)
    val lessonId = reference("lesson_id", LessonTable).nullable()
    val status = pgEnum<StudentVocabStatus>("status", "STUDENT_VOCAB_STATUS").default(StudentVocabStatus.NEW)
    val due = timestampWithTimeZone("due").nullable()
    val stability = double("stability").nullable()
    val difficulty = double("difficulty").nullable()
    val elapsedDays = integer("elapsed_days").default(0)
    val scheduledDays = integer("scheduled_days").default(0)
    val reps = integer("reps").default(0)
    val lapses = integer("lapses").default(0)
    /** FSRS state: 0 NEW, 1 LEARNING, 2 REVIEW, 3 RELEARNING (see practice/service/Fsrs.kt). */
    val state = short("state").default(0)
    /** FSRS learning step index, null outside LEARNING / RELEARNING. */
    val step = short("step").nullable()
    val lastReview = timestampWithTimeZone("last_review").nullable()
    val addedAt = timestampWithTimeZone("added_at")

    init {
        uniqueIndex(studentId, vocabEntryId)
    }
}

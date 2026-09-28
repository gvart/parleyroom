package com.gvart.parleyroom.progress.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/** Ordered weakest to strongest evidence; NEEDS_WORK is decided separately (it wins over PRACTICED). */
enum class GrammarProgressStatus { NOT_COVERED, COVERED, PRACTICED, NEEDS_WORK }

/** The teacher's manual status per student × grammar topic; wins over the derived status. */
object GrammarProgressOverrideTable : Table("grammar_progress_overrides") {
    val studentId = reference("student_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val grammarTopicId = reference("grammar_topic_id", GrammarTopicTable, onDelete = ReferenceOption.CASCADE)
    val teacherId = reference("teacher_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val status = pgEnum<GrammarProgressStatus>("status", "GRAMMAR_PROGRESS_STATUS")
    val note = text("note").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(studentId, grammarTopicId)
}

package com.gvart.parleyroom.progress.data

import com.gvart.parleyroom.ai.data.DraftBundleTable
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

/** Grammar the student covered outside tracked lessons (e.g. onboarding); a COVERED floor for the derived status. */
object StudentGrammarCoverageTable : Table("student_grammar_coverage") {
    val studentId = reference("student_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val grammarTopicId = reference("grammar_topic_id", GrammarTopicTable, onDelete = ReferenceOption.CASCADE)
    val teacherId = reference("teacher_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val bundleId = optReference("bundle_id", DraftBundleTable, onDelete = ReferenceOption.SET_NULL)
    val coveredAt = timestampWithTimeZone("covered_at")

    override val primaryKey = PrimaryKey(studentId, grammarTopicId)
}

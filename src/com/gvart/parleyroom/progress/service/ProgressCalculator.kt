package com.gvart.parleyroom.progress.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.lesson.service.LessonAttendance
import com.gvart.parleyroom.library.transfer.CoverageSource
import com.gvart.parleyroom.progress.data.GrammarProgressOverrideTable
import com.gvart.parleyroom.progress.data.GrammarProgressStatus
import com.gvart.parleyroom.progress.transfer.GrammarCounts
import com.gvart.parleyroom.progress.transfer.GrammarEvidence
import com.gvart.parleyroom.progress.transfer.GrammarPercent
import com.gvart.parleyroom.progress.transfer.ScoredCount
import com.gvart.parleyroom.progress.transfer.WordCounts
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.roundToInt

data class ProgressConfig(
    /** A grammar topic NEEDS_WORK when the scored correctness is below this share… */
    val needsWorkBelow: Double,
    /** …with at least this many scored homework units. */
    val minScoredItems: Int,
)

data class GrammarTopicRow(val id: UUID, val name: String, val level: LanguageLevel?, val category: String?, val position: Int)

data class OverrideRow(val status: GrammarProgressStatus, val note: String?, val updatedAt: OffsetDateTime, val teacherId: UUID)

data class GrammarEvaluation(
    val topic: GrammarTopicRow,
    val derived: GrammarProgressStatus,
    val override: OverrideRow?,
    val evidence: GrammarEvidence,
) {
    val effective: GrammarProgressStatus get() = override?.status ?: derived
}

data class TopicRow(val id: UUID, val name: String, val parentId: UUID?, val levels: List<LanguageLevel>, val path: List<String>)

data class TopicEvaluation(val topic: TopicRow, val via: List<CoverageSource>, val lastLessonAt: OffsetDateTime?, val words: WordCounts) {
    val covered: Boolean get() = via.isNotEmpty()
}

/**
 * Derives student progress (API.md "Student progress") for one teacher's library. Batched over
 * students: every call runs a fixed number of grouped queries. Must run in a transaction.
 */
class ProgressCalculator(val config: ProgressConfig) {

    /** The teacher's grammar topics of [levels], checklist order (level, position, name). */
    fun grammarTopics(teacherId: UUID, levels: Collection<LanguageLevel>): List<GrammarTopicRow> {
        if (levels.isEmpty()) return emptyList()
        return GrammarTopicTable.selectAll()
            .where { (GrammarTopicTable.teacherId eq teacherId) and (GrammarTopicTable.level inList levels) }
            .orderBy(GrammarTopicTable.level to SortOrder.ASC, GrammarTopicTable.position to SortOrder.ASC, GrammarTopicTable.name to SortOrder.ASC)
            .map(::grammarRow)
    }

    fun grammarRow(row: ResultRow) = GrammarTopicRow(
        id = row[GrammarTopicTable.id].value,
        name = row[GrammarTopicTable.name],
        level = row[GrammarTopicTable.level],
        category = row[GrammarTopicTable.category],
        position = row[GrammarTopicTable.position],
    )

    /** Per student, one evaluation per topic in [topics] order. */
    fun grammar(teacherId: UUID, studentIds: Collection<UUID>, topics: List<GrammarTopicRow>): Map<UUID, List<GrammarEvaluation>> {
        if (studentIds.isEmpty()) return emptyMap()
        if (topics.isEmpty()) return studentIds.associateWith { emptyList() }
        val students = uuidArray(studentIds)
        val topicIds = uuidArray(topics.map { it.id })

        data class Lessons(val count: Int, val last: OffsetDateTime)
        val lessons = Sql.rows(
            "SELECT t.grammar_topic_id, ls.student_id, count(DISTINCT l.id), max(l.scheduled_at) " +
                    "FROM lesson_grammar_topics t JOIN lessons l ON l.id = t.lesson_id " +
                    "JOIN lesson_students ls ON ls.lesson_id = l.id " +
                    "WHERE l.teacher_id = ? AND ls.student_id = ANY(?::uuid[]) AND t.grammar_topic_id = ANY(?::uuid[]) " +
                    "AND ${LessonAttendance.TOOK_PLACE} GROUP BY 1, 2",
            teacherId, students, topicIds,
        ) { rs -> (rs.uuid(1) to rs.uuid(2)) to Lessons(rs.getInt(3), rs.time(4)!!) }.toMap()

        data class Homework(val count: Int, val last: OffsetDateTime?, val scored: ScoredCount)
        // Item source tags: a DOCUMENT item's source document, a MATERIAL item's material (read live).
        // Scored units: final verdict (teacher override ?? auto) of reviewed homework only.
        val homework = Sql.rows(
            """
            SELECT x.g, h.student_id, count(DISTINCT h.id), max(h.submitted_at),
                   count(v.verdict), count(*) FILTER (WHERE v.verdict)
            FROM homework h
            JOIN assignments a ON a.id = h.assignment_id
            JOIN assignment_items ai ON ai.assignment_id = a.id
            JOIN LATERAL (
                SELECT dg.grammar_topic_id AS g FROM document_grammar_topics dg
                WHERE ai.kind = 'DOCUMENT' AND dg.document_id = ai.document_id
                UNION
                SELECT mg.grammar_topic_id FROM material_grammar_topics mg
                WHERE ai.kind = 'MATERIAL' AND mg.material_id = ai.material_id
            ) x ON x.g = ANY(?::uuid[])
            LEFT JOIN LATERAL (
                SELECT COALESCE(ha.teacher_correct,
                                CASE ha.auto_result WHEN 'CORRECT' THEN true WHEN 'INCORRECT' THEN false END) AS verdict
                FROM homework_answers ha
                WHERE ha.homework_id = h.id AND ha.assignment_item_id = ai.id AND h.status IN ('REVIEWED', 'DONE')
            ) v ON true
            WHERE a.teacher_id = ? AND h.student_id = ANY(?::uuid[]) AND h.attempt >= 1
            GROUP BY 1, 2
            """.trimIndent(),
            topicIds, teacherId, students,
        ) { rs -> (rs.uuid(1) to rs.uuid(2)) to Homework(rs.getInt(3), rs.time(4), ScoredCount(correct = rs.getInt(6), total = rs.getInt(5))) }
            .toMap()

        val overrides = GrammarProgressOverrideTable.selectAll()
            .where {
                (GrammarProgressOverrideTable.studentId inList studentIds) and
                        (GrammarProgressOverrideTable.grammarTopicId inList topics.map { it.id })
            }
            .associate {
                (it[GrammarProgressOverrideTable.grammarTopicId].value to it[GrammarProgressOverrideTable.studentId].value) to OverrideRow(
                    status = it[GrammarProgressOverrideTable.status],
                    note = it[GrammarProgressOverrideTable.note],
                    updatedAt = it[GrammarProgressOverrideTable.updatedAt].withOffsetSameInstant(ZoneOffset.UTC),
                    teacherId = it[GrammarProgressOverrideTable.teacherId].value,
                )
            }

        return studentIds.associateWith { student ->
            topics.map { topic ->
                val key = topic.id to student
                val lesson = lessons[key]
                val hw = homework[key]
                val scored = hw?.scored ?: ScoredCount(0, 0)
                GrammarEvaluation(
                    topic = topic,
                    derived = derive(lesson?.count ?: 0, hw?.count ?: 0, scored),
                    override = overrides[key],
                    evidence = GrammarEvidence(
                        lessonCount = lesson?.count ?: 0,
                        lastLessonAt = lesson?.last,
                        homeworkScored = scored,
                        lastPracticedAt = hw?.last,
                    ),
                )
            }
        }
    }

    fun derive(lessonCount: Int, practicedCount: Int, scored: ScoredCount): GrammarProgressStatus = when {
        scored.total >= config.minScoredItems && scored.correct.toDouble() / scored.total < config.needsWorkBelow ->
            GrammarProgressStatus.NEEDS_WORK
        practicedCount > 0 -> GrammarProgressStatus.PRACTICED
        lessonCount > 0 -> GrammarProgressStatus.COVERED
        else -> GrammarProgressStatus.NOT_COVERED
    }

    /** Per student, the teacher's topics whose `levels` pass [relevant], ordered by full path (tree order). */
    fun topics(teacherId: UUID, studentIds: Collection<UUID>, relevant: (List<LanguageLevel>) -> Boolean): Map<UUID, List<TopicEvaluation>> {
        if (studentIds.isEmpty()) return emptyMap()
        val all = TopicTable.selectAll().where { TopicTable.teacherId eq teacherId }.associate {
            it[TopicTable.id].value to Triple(it[TopicTable.name], it[TopicTable.parentId]?.value, it[TopicTable.levels].map(LanguageLevel::valueOf))
        }
        fun path(id: UUID): List<String> =
            generateSequence(all[id]?.second) { all[it]?.second }.take(20).mapNotNull { all[it]?.first }.toList().reversed()
        val rows = all.filter { (_, t) -> relevant(t.third) }
            .map { (id, t) -> TopicRow(id, t.first, t.second, t.third.sorted(), path(id)) }
            .sortedBy { (it.path + it.name).joinToString(" > ").lowercase() }
        if (rows.isEmpty()) return studentIds.associateWith { emptyList() }
        val students = uuidArray(studentIds)

        val lessons = Sql.rows(
            "SELECT t.topic_id, ls.student_id, max(l.scheduled_at) FROM lesson_topics t " +
                    "JOIN lessons l ON l.id = t.lesson_id JOIN lesson_students ls ON ls.lesson_id = l.id " +
                    "WHERE l.teacher_id = ? AND ls.student_id = ANY(?::uuid[]) AND ${LessonAttendance.TOOK_PLACE} GROUP BY 1, 2",
            teacherId, students,
        ) { rs -> (rs.uuid(1) to rs.uuid(2)) to rs.time(3)!! }.toMap()
        val words = Sql.rows(
            "SELECT t.topic_id, sv.student_id, count(*), count(*) FILTER (WHERE sv.status = 'LEARNED') " +
                    "FROM vocab_entry_topics t JOIN vocab_entries e ON e.id = t.vocab_entry_id " +
                    "JOIN student_vocab sv ON sv.vocab_entry_id = e.id " +
                    "WHERE e.teacher_id = ? AND sv.student_id = ANY(?::uuid[]) GROUP BY 1, 2",
            teacherId, students,
        ) { rs -> (rs.uuid(1) to rs.uuid(2)) to WordCounts(learned = rs.getInt(4), total = rs.getInt(3)) }.toMap()

        return studentIds.associateWith { student ->
            rows.map { topic ->
                val lastLesson = lessons[topic.id to student]
                val w = words[topic.id to student] ?: WordCounts(0, 0)
                TopicEvaluation(
                    topic = topic,
                    via = listOfNotNull(CoverageSource.LESSON.takeIf { lastLesson != null }, CoverageSource.VOCAB.takeIf { w.total > 0 }),
                    lastLessonAt = lastLesson,
                    words = w,
                )
            }
        }
    }

    /** (total, learned) of the student's words from the teacher's library. */
    fun vocab(teacherId: UUID, studentId: UUID): WordCounts = Sql.rows(
        "SELECT count(*), count(*) FILTER (WHERE sv.status = 'LEARNED') FROM student_vocab sv " +
                "JOIN vocab_entries e ON e.id = sv.vocab_entry_id WHERE e.teacher_id = ? AND sv.student_id = ?",
        teacherId, studentId,
    ) { rs -> WordCounts(learned = rs.getInt(2), total = rs.getInt(1)) }.single()

    companion object {

        fun counts(statuses: List<GrammarProgressStatus>): GrammarCounts {
            val total = statuses.size
            fun n(s: GrammarProgressStatus) = statuses.count { it == s }
            val notCovered = n(GrammarProgressStatus.NOT_COVERED)
            val covered = n(GrammarProgressStatus.COVERED)
            val practiced = n(GrammarProgressStatus.PRACTICED)
            val needsWork = n(GrammarProgressStatus.NEEDS_WORK)
            val percent = if (total == 0) null else {
                fun p(x: Int) = (100.0 * x / total).roundToInt()
                GrammarPercent(p(notCovered), p(covered), p(practiced), p(needsWork), reached = 100 - p(notCovered))
            }
            return GrammarCounts(total, notCovered, covered, practiced, needsWork, percent)
        }

        fun percent(part: Int, total: Int): Int? = if (total == 0) null else (100.0 * part / total).roundToInt()

        private fun uuidArray(ids: Collection<UUID>) = ids.joinToString(",", "{", "}")

        private fun java.sql.ResultSet.uuid(i: Int): UUID = getObject(i, UUID::class.java)

        private fun java.sql.ResultSet.time(i: Int): OffsetDateTime? =
            getObject(i, OffsetDateTime::class.java)?.withOffsetSameInstant(ZoneOffset.UTC)
    }
}

package com.gvart.parleyroom.admin.service

import com.gvart.parleyroom.admin.transfer.AdminStatsResponse
import com.gvart.parleyroom.admin.transfer.ContentStats
import com.gvart.parleyroom.admin.transfer.DailyCount
import com.gvart.parleyroom.admin.transfer.HomeworkStats
import com.gvart.parleyroom.admin.transfer.LessonDay
import com.gvart.parleyroom.admin.transfer.LessonStats
import com.gvart.parleyroom.admin.transfer.PracticeDay
import com.gvart.parleyroom.admin.transfer.PracticeStats
import com.gvart.parleyroom.admin.transfer.TopTeacher
import com.gvart.parleyroom.admin.transfer.UserStats
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Platform metrics, aggregated in SQL. Range metrics cover the inclusive UTC days [from, to];
 * byStatus / totals are current snapshots; active* windows are relative to now.
 */
class AdminStatsService {

    fun stats(from: LocalDate?, to: LocalDate?): AdminStatsResponse {
        val end = to ?: LocalDate.now(ZoneOffset.UTC)
        val start = from ?: end.minusDays(DEFAULT_DAYS - 1)
        if (start.isAfter(end)) throw BadRequestException("from must not be after to")
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS)
            throw BadRequestException("Date range must not exceed $MAX_DAYS days")

        val range = Range(start, end)
        return transaction {
            AdminStatsResponse(
                from = start.toString(),
                to = end.toString(),
                users = users(range),
                lessons = lessons(range),
                homework = homework(range),
                practice = practice(range),
                content = content(),
            )
        }
    }

    private fun users(range: Range): UserStats {
        val signups = daily("users", "created_at", range)
        return UserStats(
            total = Sql.long("SELECT count(*) FROM users"),
            byRole = countBy("SELECT role::text, count(*) FROM users GROUP BY 1", UserRole.entries),
            byStatus = countBy("SELECT status::text, count(*) FROM users GROUP BY 1", UserStatus.entries),
            newInRange = count("users", "created_at", range),
            active7d = activeUsers(7),
            active30d = activeUsers(30),
            signupsByDay = range.days.map { DailyCount(it.toString(), signups[it] ?: 0) },
        )
    }

    private fun activeUsers(days: Int): Long = Sql.long(
        "SELECT count(*) FROM (" +
            "SELECT id FROM users WHERE last_login_at >= now() - interval '$days days' " +
            "UNION SELECT user_id FROM learning_activity WHERE occurred_at >= now() - interval '$days days'" +
            ") active"
    )

    private fun lessons(range: Range): LessonStats {
        val completed = daily("lessons", COMPLETED_AT, range, "status = 'COMPLETED'")
        val cancelled = daily("lessons", CANCELLED_AT, range, "status = 'CANCELLED'")
        val topTeachers = Sql.rows(
            "SELECT u.id, u.first_name, u.last_name, count(*) FROM lessons l JOIN users u ON u.id = l.teacher_id " +
                "WHERE l.status = 'COMPLETED' AND ${range.condition(COMPLETED_AT)} " +
                "GROUP BY u.id, u.first_name, u.last_name ORDER BY 4 DESC, u.last_name, u.first_name LIMIT 5",
            *range.args,
        ) { rs -> TopTeacher(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) }

        return LessonStats(
            byStatus = countBy("SELECT status::text, count(*) FROM lessons GROUP BY 1", LessonStatus.entries),
            scheduledInRange = count("lessons", "scheduled_at", range, "status <> 'CANCELLED'"),
            completedInRange = completed.values.sum(),
            cancelledInRange = cancelled.values.sum(),
            byDay = range.days.map { LessonDay(it.toString(), completed[it] ?: 0, cancelled[it] ?: 0) },
            topTeachers = topTeachers,
        )
    }

    private fun homework(range: Range): HomeworkStats {
        val avgReviewHours = Sql.rows(
            "SELECT avg(extract(epoch FROM reviewed_at - submitted_at)) / 3600 FROM homework " +
                "WHERE submitted_at IS NOT NULL AND reviewed_at >= submitted_at AND ${range.condition("reviewed_at")} " +
                "HAVING count(*) > 0", // no row (-> null) instead of a NULL average
            *range.args,
        ) { rs -> rs.getDouble(1) }.firstOrNull()

        return HomeworkStats(
            byStatus = countBy("SELECT status::text, count(*) FROM homework GROUP BY 1", HomeworkStatus.entries),
            assignedInRange = count("homework", "created_at", range),
            submittedInRange = count("homework", "submitted_at", range),
            reviewedInRange = count("homework", "reviewed_at", range),
            avgReviewHours = avgReviewHours,
        )
    }

    private fun practice(range: Range): PracticeStats {
        val reviews = daily("vocab_reviews", "reviewed_at", range)
        val sentences = daily("student_vocab_sentences", "created_at", range)
        val submitted = daily("homework", "submitted_at", range)
        val completed = daily("lessons", COMPLETED_AT, range, "status = 'COMPLETED'")
        return PracticeStats(
            vocabReviewsInRange = reviews.values.sum(),
            sentencesInRange = sentences.values.sum(),
            activeLearners7d = Sql.long(
                "SELECT count(DISTINCT user_id) FROM learning_activity WHERE occurred_at >= now() - interval '7 days'"
            ),
            byDay = range.days.map {
                PracticeDay(it.toString(), reviews[it] ?: 0, sentences[it] ?: 0, submitted[it] ?: 0, completed[it] ?: 0)
            },
        )
    }

    private fun content() = ContentStats(
        materials = Sql.long("SELECT count(*) FROM materials"),
        documents = Sql.long("SELECT count(*) FROM documents"),
        vocabEntries = Sql.long("SELECT count(*) FROM vocab_entries"),
        studentVocabByStatus = countBy(
            "SELECT status::text, count(*) FROM student_vocab GROUP BY 1",
            StudentVocabStatus.entries,
        ),
    )

    private fun count(table: String, column: String, range: Range, where: String = "TRUE"): Long =
        Sql.long("SELECT count(*) FROM $table WHERE $where AND ${range.condition(column)}", *range.args)

    /** Per-UTC-day counts; days without rows are absent (callers fill zeros). */
    private fun daily(table: String, column: String, range: Range, where: String = "TRUE"): Map<LocalDate, Long> =
        Sql.rows(
            "SELECT ($column AT TIME ZONE 'UTC')::date::text, count(*) FROM $table " +
                "WHERE $where AND ${range.condition(column)} GROUP BY 1",
            *range.args,
        ) { rs -> LocalDate.parse(rs.getString(1)) to rs.getLong(2) }.toMap()

    private fun <E : Enum<E>> countBy(sql: String, entries: List<E>): Map<E, Long> {
        val counts = Sql.rows(sql) { rs -> rs.getString(1) to rs.getLong(2) }.toMap()
        return entries.associateWith { counts[it.name] ?: 0 }
    }

    private class Range(start: LocalDate, end: LocalDate) {
        val days: List<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val args: Array<Any> = arrayOf(
            start.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
            end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
        )

        fun condition(column: String) = "$column >= ?::timestamptz AND $column < ?::timestamptz"
    }

    private companion object {
        const val DEFAULT_DAYS = 30L
        const val MAX_DAYS = 366L
        // A completed lesson counts on the day it ended (scheduled time if never started live).
        const val COMPLETED_AT = "COALESCE(ended_at, scheduled_at)"
        const val CANCELLED_AT = "COALESCE(cancelled_at, updated_at)"
    }
}

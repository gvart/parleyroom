package com.gvart.parleyroom.goal.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.GoalType
import com.gvart.parleyroom.goal.transfer.GoalGrammarCounts
import com.gvart.parleyroom.goal.transfer.GoalInput
import com.gvart.parleyroom.goal.transfer.GoalPatch
import com.gvart.parleyroom.goal.transfer.GoalProgress
import com.gvart.parleyroom.goal.transfer.GoalResponse
import com.gvart.parleyroom.goal.transfer.GoalTopicCounts
import com.gvart.parleyroom.progress.data.GrammarProgressStatus
import com.gvart.parleyroom.progress.service.ProgressCalculator
import com.gvart.parleyroom.progress.service.TopicEvaluation
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Auto-tracked goals (brief §5.9, API.md "Goals"): the teacher sets a target, progress is computed
 * from the student's progress in the teacher's library on every read.
 */
class GoalService(private val calculator: ProgressCalculator) {

    private data class Computed(val percent: Int?, val grammar: GoalGrammarCounts, val topics: GoalTopicCounts)

    fun list(principal: UserPrincipal, studentId: UUID?, statuses: List<GoalStatus>?): List<GoalResponse> = transaction {
        val query = GoalTable.selectAll()
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> query.andWhere { GoalTable.teacherId eq principal.id }
            UserRole.STUDENT -> query.andWhere { GoalTable.studentId eq principal.id }
        }
        if (studentId != null) {
            AuthorizationHelper.requireAccessToStudent(studentId, principal)
            query.andWhere { GoalTable.studentId eq studentId }
        }
        if (!statuses.isNullOrEmpty()) query.andWhere { GoalTable.status inList statuses }
        val rows = query.toList()

        toResponses(rows).sortedWith(
            compareBy<GoalResponse> { it.status != GoalStatus.ACTIVE }
                .thenBy(nullsLast()) { it.targetDate }
                .thenByDescending { it.createdAt },
        )
    }

    fun get(goalId: UUID, principal: UserPrincipal): GoalResponse = transaction {
        toResponses(listOf(findVisible(goalId, principal))).single()
    }

    fun create(input: GoalInput, principal: UserPrincipal): GoalResponse = transaction {
        if (principal.role != UserRole.TEACHER) throw ForbiddenException("Only teachers can set goals")
        val studentId = input.studentId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: invalid("studentId must be a uuid", "/studentId")
        AuthorizationHelper.requireAccessToStudent(studentId, principal)

        val type = input.type?.let { raw -> GoalType.entries.firstOrNull { it.name == raw } }
            ?: invalid("type must be one of ${GoalType.entries}", "/type")
        val targetLevel = level(input.targetLevel) ?: invalid("targetLevel is required", "/targetLevel")
        val examName = input.examName?.trim()?.takeIf { it.isNotEmpty() }
        when (type) {
            GoalType.EXAM -> if (examName == null) invalid("examName is required for EXAM goals", "/examName")
            GoalType.LEVEL -> if (examName != null) invalid("LEVEL goals have no examName", "/examName")
        }
        examName?.let(::checkExamName)
        val targetDate = input.targetDate?.let(::date)
        if (type == GoalType.EXAM && targetDate == null) invalid("targetDate is required for EXAM goals", "/targetDate")
        if (targetDate != null && targetDate < LocalDate.now(zoneOf(principal.id)))
            invalid("targetDate must not be in the past", "/targetDate")
        val note = note(input.note)

        val baseline = compute(principal.id, listOf(studentId to targetLevel)).getValue(studentId to targetLevel).percent
        val now = OffsetDateTime.now()
        val id = GoalTable.insertAndGetId {
            it[GoalTable.studentId] = studentId
            it[teacherId] = principal.id
            it[GoalTable.type] = type
            it[GoalTable.examName] = examName
            it[GoalTable.targetLevel] = targetLevel
            it[GoalTable.targetDate] = targetDate?.toKotlinLocalDate()
            it[GoalTable.note] = note
            it[baselinePercent] = baseline
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        toResponses(listOf(find(id))).single()
    }

    fun patch(goalId: UUID, patch: GoalPatch, principal: UserPrincipal): GoalResponse = transaction {
        val row = findVisible(goalId, principal)
        if (row[GoalTable.teacherId].value != principal.id) throw ForbiddenException("Only the goal's teacher can change it")
        val type = row[GoalTable.type]

        val examName = patch.examName?.trim()
        if (examName != null) {
            if (type == GoalType.LEVEL) invalid("LEVEL goals have no examName", "/examName")
            if (examName.isEmpty()) invalid("examName must not be blank", "/examName")
            checkExamName(examName)
        }
        val targetLevel = patch.targetLevel?.let { level(it) }
        val targetDate = patch.targetDate?.let(::date)
        if (patch.clearTargetDate && type == GoalType.EXAM) invalid("EXAM goals need a targetDate", "/clearTargetDate")
        val note = note(patch.note)
        val status = patch.status?.let { raw ->
            GoalStatus.entries.firstOrNull { it.name == raw } ?: invalid("status must be one of ${GoalStatus.entries}", "/status")
        }

        val now = OffsetDateTime.now()
        GoalTable.update({ GoalTable.id eq goalId }) {
            if (examName != null) it[GoalTable.examName] = examName
            if (targetLevel != null) it[GoalTable.targetLevel] = targetLevel
            if (patch.clearTargetDate) it[GoalTable.targetDate] = null
            else if (targetDate != null) it[GoalTable.targetDate] = targetDate.toKotlinLocalDate()
            if (patch.clearNote) it[GoalTable.note] = null
            else if (note != null) it[GoalTable.note] = note
            if (status != null && status != row[GoalTable.status]) {
                it[GoalTable.status] = status
                it[statusChangedAt] = now
            }
            it[updatedAt] = now
        }
        toResponses(listOf(find(goalId))).single()
    }

    fun delete(goalId: UUID, principal: UserPrincipal) = transaction {
        val row = findVisible(goalId, principal)
        AuthorizationHelper.requireOwnerOrAdmin(row[GoalTable.teacherId].value, principal, "Only the goal's teacher can delete it")
        GoalTable.deleteWhere { GoalTable.id eq goalId }
    }

    /** Batched: one progress computation per distinct teacher. */
    private fun toResponses(rows: List<ResultRow>): List<GoalResponse> {
        if (rows.isEmpty()) return emptyList()
        val computed = rows.groupBy { it[GoalTable.teacherId].value }.flatMap { (teacher, goals) ->
            val pairs = goals.map { it[GoalTable.studentId].value to it[GoalTable.targetLevel] }.distinct()
            compute(teacher, pairs).map { (key, value) -> (teacher to key) to value }
        }.toMap()
        val zones = UserTable.select(UserTable.id, UserTable.timezone)
            .where { UserTable.id inList rows.map { it[GoalTable.studentId].value }.distinct() }
            .associate { it[UserTable.id].value to zone(it[UserTable.timezone]) }
        return rows.map { row ->
            val student = row[GoalTable.studentId].value
            val progress = computed.getValue(row[GoalTable.teacherId].value to (student to row[GoalTable.targetLevel]))
            toResponse(row, progress, zones[student] ?: ZoneOffset.UTC)
        }
    }

    /** Goal progress per (student, target level) in [teacher]'s library (API.md formula). */
    private fun compute(teacher: UUID, pairs: List<Pair<UUID, LanguageLevel>>): Map<Pair<UUID, LanguageLevel>, Computed> {
        val students = pairs.map { it.first }.distinct()
        val levels = pairs.map { it.second }.toSet()
        val grammar = calculator.grammar(teacher, students, calculator.grammarTopics(teacher, levels))
        val topics = calculator.topics(teacher, students) { tl -> tl.any { it in levels } }
        return pairs.associateWith { (student, level) ->
            computed(
                grammar[student].orEmpty().filter { it.topic.level == level }.map { it.effective },
                topics[student].orEmpty().filter { level in it.topic.levels },
            )
        }
    }

    private fun computed(grammar: List<GrammarProgressStatus>, topics: List<TopicEvaluation>): Computed {
        fun n(s: GrammarProgressStatus) = grammar.count { it == s }
        val counts = GoalGrammarCounts(
            total = grammar.size,
            practiced = n(GrammarProgressStatus.PRACTICED),
            covered = n(GrammarProgressStatus.COVERED),
            needsWork = n(GrammarProgressStatus.NEEDS_WORK),
            notCovered = n(GrammarProgressStatus.NOT_COVERED),
        )
        val covered = topics.count { it.covered }
        val percent = if (grammar.isEmpty()) null else {
            // PRACTICED 1, lesson-only COVERED ½, NEEDS_WORK and NOT_COVERED 0.
            val grammarScore = (counts.practiced + 0.5 * counts.covered) / grammar.size
            val score = if (topics.isEmpty()) grammarScore
            else GRAMMAR_WEIGHT * grammarScore + (1 - GRAMMAR_WEIGHT) * covered.toDouble() / topics.size
            (100 * score).roundToInt()
        }
        return Computed(percent, counts, GoalTopicCounts(topics.size, covered))
    }

    private fun toResponse(row: ResultRow, computed: Computed, zone: ZoneId): GoalResponse {
        val today = LocalDate.now(zone)
        val targetDate = row[GoalTable.targetDate]?.toJavaLocalDate()
        val status = row[GoalTable.status]
        val baseline = row[GoalTable.baselinePercent]
        val expected = if (status == GoalStatus.ACTIVE && targetDate != null && computed.percent != null) {
            val start = row[GoalTable.createdAt].atZoneSameInstant(zone).toLocalDate()
            val span = maxOf(1L, ChronoUnit.DAYS.between(start, targetDate))
            val elapsed = ChronoUnit.DAYS.between(start, today).coerceIn(0L, span)
            val base = baseline ?: 0
            (base + elapsed.toDouble() / span * (100 - base)).roundToInt()
        } else null
        return GoalResponse(
            id = row[GoalTable.id].value.toString(),
            studentId = row[GoalTable.studentId].value.toString(),
            teacherId = row[GoalTable.teacherId].value.toString(),
            type = row[GoalTable.type],
            examName = row[GoalTable.examName],
            targetLevel = row[GoalTable.targetLevel],
            targetDate = targetDate?.toString(),
            note = row[GoalTable.note],
            status = status,
            statusChangedAt = row[GoalTable.statusChangedAt],
            createdAt = row[GoalTable.createdAt],
            updatedAt = row[GoalTable.updatedAt],
            baselinePercent = baseline,
            progress = GoalProgress(
                percent = computed.percent,
                checklistEmpty = computed.grammar.total == 0,
                grammar = computed.grammar,
                topics = computed.topics,
                grammarDone = computed.grammar.practiced,
                grammarTotal = computed.grammar.total,
                topicsDone = computed.topics.covered,
                topicsTotal = computed.topics.total,
                daysLeft = targetDate?.let { ChronoUnit.DAYS.between(today, it).toInt() },
                expectedPercent = expected,
                onTrack = expected?.let { computed.percent!! >= it - ON_TRACK_TOLERANCE },
            ),
        )
    }

    private fun find(goalId: UUID): ResultRow =
        GoalTable.selectAll().where { GoalTable.id eq goalId }.singleOrNull()
            ?: throw NotFoundException("Goal not found", code = "GOAL_NOT_FOUND")

    /** Students see their own goals, teachers the ones they set, admins all; anything else is 404. */
    private fun findVisible(goalId: UUID, principal: UserPrincipal): ResultRow {
        val row = find(goalId)
        val visible = when (principal.role) {
            UserRole.ADMIN -> true
            UserRole.TEACHER -> row[GoalTable.teacherId].value == principal.id
            UserRole.STUDENT -> row[GoalTable.studentId].value == principal.id
        }
        if (!visible) throw NotFoundException("Goal not found", code = "GOAL_NOT_FOUND")
        return row
    }

    private fun level(raw: String?): LanguageLevel? = raw?.let { value ->
        LanguageLevel.entries.firstOrNull { it.name == value } ?: invalid("targetLevel must be one of ${LanguageLevel.entries}", "/targetLevel")
    }

    private fun date(raw: String): LocalDate = try {
        LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
        invalid("targetDate must be YYYY-MM-DD", "/targetDate")
    }

    private fun checkExamName(name: String) {
        if (name.length > MAX_EXAM_NAME) invalid("examName must be at most $MAX_EXAM_NAME characters", "/examName")
    }

    private fun note(raw: String?): String? {
        val note = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (note.length > MAX_NOTE) invalid("note must be at most $MAX_NOTE characters", "/note")
        return note
    }

    private fun zoneOf(userId: UUID): ZoneId =
        UserTable.select(UserTable.timezone).where { UserTable.id eq userId }.singleOrNull()
            ?.let { zone(it[UserTable.timezone]) } ?: ZoneOffset.UTC

    private fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneOffset.UTC)

    private fun invalid(message: String, pointer: String): Nothing = throw BadRequestException(message, "GOAL_INVALID", pointer)

    companion object {
        const val GRAMMAR_WEIGHT = 0.8
        const val ON_TRACK_TOLERANCE = 10
        const val MAX_EXAM_NAME = 100
        const val MAX_NOTE = 2000
    }
}

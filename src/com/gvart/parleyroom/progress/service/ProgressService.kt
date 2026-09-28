package com.gvart.parleyroom.progress.service

import com.gvart.parleyroom.activity.service.StreakService
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.progress.data.GrammarProgressOverrideTable
import com.gvart.parleyroom.progress.data.GrammarProgressStatus
import com.gvart.parleyroom.progress.transfer.CoveredCount
import com.gvart.parleyroom.progress.transfer.GrammarOverride
import com.gvart.parleyroom.progress.transfer.GrammarOverrideRequest
import com.gvart.parleyroom.progress.transfer.GrammarProgressItem
import com.gvart.parleyroom.progress.transfer.GrammarProgressLevel
import com.gvart.parleyroom.progress.transfer.ProgressSummary
import com.gvart.parleyroom.progress.transfer.ProgressThresholds
import com.gvart.parleyroom.progress.transfer.StreakSummary
import com.gvart.parleyroom.progress.transfer.StudentProgress
import com.gvart.parleyroom.progress.transfer.TopicProgressItem
import com.gvart.parleyroom.progress.transfer.VocabCount
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime
import java.util.UUID

/** The Progress tab (brief §5.6): access rules on top of [ProgressCalculator]. */
class ProgressService(
    private val calculator: ProgressCalculator,
    private val streakService: StreakService,
) {

    fun progress(
        studentId: UUID,
        principal: UserPrincipal,
        teacherId: UUID?,
        level: LanguageLevel?,
        includeLower: Boolean,
    ): StudentProgress = transaction {
        val teacher = resolveTeacher(studentId, principal, teacherId)
        val student = UserTable.findByIdOrThrow(studentId, "User")
        val used = level ?: student[UserTable.level]
        val levels = when {
            used == null -> emptyList()
            includeLower -> LanguageLevel.entries.filter { it <= used }
            else -> listOf(used)
        }

        val evaluations = calculator.grammar(teacher, listOf(studentId), calculator.grammarTopics(teacher, levels))[studentId].orEmpty()
        val showNote = principal.role != UserRole.STUDENT
        val grammar = levels.map { l ->
            val ofLevel = evaluations.filter { it.topic.level == l }
            GrammarProgressLevel(
                level = l,
                checklistEmpty = ofLevel.isEmpty(),
                counts = ProgressCalculator.counts(ofLevel.map { it.effective }),
                items = ofLevel.map { toItem(it, showNote) },
            )
        }
        val topics = if (levels.isEmpty()) emptyList()
        else calculator.topics(teacher, listOf(studentId)) { tl -> tl.isEmpty() || tl.any { it in levels } }[studentId].orEmpty()
        val vocab = calculator.vocab(teacher, studentId)
        val streak = streakService.getStreak(studentId, principal)
        val coveredTopics = topics.count { it.covered }

        StudentProgress(
            studentId = studentId.toString(),
            teacherId = teacher.toString(),
            level = used,
            levels = levels,
            checklistEmpty = evaluations.isEmpty(),
            thresholds = ProgressThresholds(calculator.config.needsWorkBelow, calculator.config.minScoredItems),
            grammar = grammar,
            topics = topics.map {
                TopicProgressItem(
                    id = it.topic.id.toString(),
                    name = it.topic.name,
                    parentId = it.topic.parentId?.toString(),
                    levels = it.topic.levels,
                    path = it.topic.path,
                    covered = it.covered,
                    via = it.via,
                    lastLessonAt = it.lastLessonAt,
                    words = it.words,
                )
            },
            summary = ProgressSummary(
                grammar = ProgressCalculator.counts(evaluations.map { it.effective }),
                topics = CoveredCount(topics.size, coveredTopics, ProgressCalculator.percent(coveredTopics, topics.size)),
                vocab = VocabCount(vocab.total, vocab.learned, ProgressCalculator.percent(vocab.learned, vocab.total)),
                streak = StreakSummary(streak.current, streak.longest, streak.todayDone),
            ),
        )
    }

    fun setOverride(studentId: UUID, grammarTopicId: UUID, request: GrammarOverrideRequest, principal: UserPrincipal): GrammarProgressItem =
        transaction {
            val topic = requireOverrideAccess(studentId, grammarTopicId, principal)
            val status = request.status?.let { raw -> GrammarProgressStatus.entries.firstOrNull { it.name == raw } }
                ?: throw BadRequestException("status must be one of ${GrammarProgressStatus.entries}", "GRAMMAR_OVERRIDE_INVALID", "/status")
            val note = request.note?.trim()?.takeIf { it.isNotEmpty() }
            if (note != null && note.length > MAX_NOTE)
                throw BadRequestException("note must be at most $MAX_NOTE characters", "GRAMMAR_OVERRIDE_INVALID", "/note")

            GrammarProgressOverrideTable.upsert {
                it[GrammarProgressOverrideTable.studentId] = studentId
                it[GrammarProgressOverrideTable.grammarTopicId] = grammarTopicId
                it[teacherId] = principal.id
                it[GrammarProgressOverrideTable.status] = status
                it[GrammarProgressOverrideTable.note] = note
                it[updatedAt] = OffsetDateTime.now()
            }
            item(principal.id, studentId, topic)
        }

    fun deleteOverride(studentId: UUID, grammarTopicId: UUID, principal: UserPrincipal): GrammarProgressItem = transaction {
        val topic = requireOverrideAccess(studentId, grammarTopicId, principal)
        GrammarProgressOverrideTable.deleteWhere {
            (GrammarProgressOverrideTable.studentId eq studentId) and (GrammarProgressOverrideTable.grammarTopicId eq grammarTopicId)
        }
        item(principal.id, studentId, topic)
    }

    private fun item(teacher: UUID, studentId: UUID, topic: GrammarTopicRow): GrammarProgressItem =
        toItem(calculator.grammar(teacher, listOf(studentId), listOf(topic)).getValue(studentId).single(), showNote = true)

    /** Teacher only, linked to the student, owning the grammar topic. */
    private fun requireOverrideAccess(studentId: UUID, grammarTopicId: UUID, principal: UserPrincipal): GrammarTopicRow {
        LibraryAccess.requireTeacher(principal)
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
        val row = GrammarTopicTable.selectAll()
            .where { (GrammarTopicTable.id eq grammarTopicId) and (GrammarTopicTable.teacherId eq principal.id) }
            .singleOrNull() ?: throw NotFoundException("Grammar topic not found", code = "GRAMMAR_TOPIC_NOT_FOUND")
        return calculator.grammarRow(row)
    }

    /** Teachers: themselves. Students / admins: [teacherId], else the student's earliest teacher. */
    private fun resolveTeacher(studentId: UUID, principal: UserPrincipal, teacherId: UUID?): UUID {
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
        if (principal.role == UserRole.TEACHER) return principal.id
        val query = TeacherStudentTable.select(TeacherStudentTable.teacherId).where { TeacherStudentTable.studentId eq studentId }
        if (teacherId != null) query.andWhere { TeacherStudentTable.teacherId eq teacherId }
        return query.orderBy(TeacherStudentTable.startedAt).firstOrNull()?.get(TeacherStudentTable.teacherId)?.value
            ?: throw NotFoundException("Student has no teacher", code = "TEACHER_STUDENT_NOT_FOUND")
    }

    private fun toItem(e: GrammarEvaluation, showNote: Boolean) = GrammarProgressItem(
        id = e.topic.id.toString(),
        name = e.topic.name,
        level = e.topic.level,
        category = e.topic.category,
        position = e.topic.position,
        derived = e.derived,
        override = e.override?.let {
            GrammarOverride(it.status, it.note.takeIf { showNote }, it.updatedAt, it.teacherId.toString())
        },
        effective = e.effective,
        evidence = e.evidence,
    )

    companion object {
        const val MAX_NOTE = 2000
    }
}

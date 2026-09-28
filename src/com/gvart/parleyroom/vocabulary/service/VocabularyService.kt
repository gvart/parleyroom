package com.gvart.parleyroom.vocabulary.service

import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.service.LearningActivityRecorder
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.singleOrNotFound
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTopicTable
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** A student's vocabulary (student_vocab joined with the teacher's library entry). */
class VocabularyService {

    data class Filters(
        val studentId: UUID? = null,
        val status: StudentVocabStatus? = null,
        val topicId: UUID? = null,
        val level: LanguageLevel? = null,
        val lessonId: UUID? = null,
        val q: String? = null,
    )

    fun getWords(principal: UserPrincipal, filters: Filters, page: PageRequest): StudentVocabPageResponse = transaction {
        val query = joined().selectAll()
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> query.andWhere {
                StudentVocabTable.studentId inSubQuery TeacherStudentTable.select(TeacherStudentTable.studentId)
                    .where { TeacherStudentTable.teacherId eq principal.id }
            }
            UserRole.STUDENT -> query.andWhere { StudentVocabTable.studentId eq principal.id }
        }

        filters.studentId?.let {
            AuthorizationHelper.requireAccessToStudent(it, principal)
            query.andWhere { StudentVocabTable.studentId eq it }
        }
        filters.status?.let { query.andWhere { StudentVocabTable.status eq it } }
        filters.level?.let { query.andWhere { VocabEntryTable.level eq it } }
        filters.lessonId?.let { query.andWhere { StudentVocabTable.lessonId eq it } }
        filters.topicId?.let { topicId ->
            query.andWhere {
                VocabEntryTable.id inSubQuery VocabEntryTopicTable.select(VocabEntryTopicTable.vocabEntryId)
                    .where { VocabEntryTopicTable.topicId eq topicId }
            }
        }
        filters.q?.takeIf { it.isNotBlank() }?.let { q ->
            query.andWhere { VocabEntryTable.lemma.lowerCase() like "%${q.trim().lowercase()}%" }
        }

        val total = query.count()
        val rows = query
            .orderBy(StudentVocabTable.addedAt to SortOrder.DESC, VocabEntryTable.lemma to SortOrder.ASC)
            .limit(page.pageSize)
            .offset(page.offset)
            .toList()
        StudentVocabPageResponse(toResponses(rows, principal), total, page.page, page.pageSize)
    }

    fun getWord(id: UUID, principal: UserPrincipal): StudentVocabResponse = transaction {
        val row = findWord(id)
        AuthorizationHelper.requireAccessToStudent(row[StudentVocabTable.studentId].value, principal)
        toResponses(listOf(row), principal).single()
    }

    fun updateStatus(id: UUID, status: StudentVocabStatus, principal: UserPrincipal): StudentVocabResponse = transaction {
        val row = findWord(id)
        AuthorizationHelper.requireAccessToStudent(row[StudentVocabTable.studentId].value, principal)
        StudentVocabTable.update({ StudentVocabTable.id eq id }) { it[StudentVocabTable.status] = status }
        toResponses(listOf(findWord(id)), principal).single()
    }

    fun deleteWord(id: UUID, principal: UserPrincipal) = transaction {
        val row = findWord(id)
        AuthorizationHelper.requireAccessToStudent(row[StudentVocabTable.studentId].value, principal)
        StudentVocabTable.deleteWhere { StudentVocabTable.id eq id }
    }

    /**
     * Simple "knew it" review, mapped onto the FSRS columns until the FSRS scheduler lands:
     * interval doubles per review (capped at 64 days); LEARNING → REVIEW after 3, LEARNED after 5.
     */
    fun reviewWord(id: UUID, principal: UserPrincipal): StudentVocabResponse = transaction {
        val row = findWord(id)
        val studentId = row[StudentVocabTable.studentId].value
        AuthorizationHelper.requireAccessToStudent(studentId, principal)

        val now = OffsetDateTime.now()
        val reps = row[StudentVocabTable.reps] + 1
        val status = when {
            reps >= 5 -> StudentVocabStatus.LEARNED
            reps >= 3 -> StudentVocabStatus.REVIEW
            else -> StudentVocabStatus.LEARNING
        }
        val scheduledDays = 1 shl minOf(reps, 6)
        val elapsedDays = row[StudentVocabTable.lastReview]?.let { Duration.between(it, now).toDays().toInt() } ?: 0

        StudentVocabTable.update({ StudentVocabTable.id eq id }) {
            it[StudentVocabTable.reps] = reps
            it[StudentVocabTable.status] = status
            it[StudentVocabTable.scheduledDays] = scheduledDays
            it[StudentVocabTable.elapsedDays] = elapsedDays
            it[due] = now.plusDays(scheduledDays.toLong())
            it[lastReview] = now
            it[state] = if (status == StudentVocabStatus.LEARNING) FSRS_LEARNING else FSRS_REVIEW
        }

        if (principal.id == studentId)
            LearningActivityRecorder.record(studentId, ActivityKind.VOCAB_REVIEW, id)

        toResponses(listOf(findWord(id)), principal).single()
    }

    private fun joined() = StudentVocabTable.join(
        VocabEntryTable, JoinType.INNER, StudentVocabTable.vocabEntryId, VocabEntryTable.id,
    )

    private fun findWord(id: UUID): ResultRow =
        joined().selectAll().where { StudentVocabTable.id eq id }.singleOrNotFound("Vocabulary word")

    /**
     * Resolves the display setting per row (lesson override → teacher–student setting →
     * level default) and strips hidden fields for students.
     */
    private fun toResponses(rows: List<ResultRow>, principal: UserPrincipal): List<StudentVocabResponse> {
        if (rows.isEmpty()) return emptyList()
        val topicsByEntry = VocabEntryService.topicIdsByEntry(rows.map { it[VocabEntryTable.id].value }.distinct())
        val settings = DisplaySettingResolver.load(rows)
        val isStudent = principal.role == UserRole.STUDENT

        return rows.map { row ->
            val display = settings.resolve(row)
            val allTranslations = row[VocabEntryTable.translations]
            val shownTranslations = if (isStudent) allTranslations.filterKeys { it in display.fields } else allTranslations
            val showExplanation = !isStudent || VocabDisplay.DE_EXPLANATION in display.fields
            val reveal = if (isStudent && display.allowTranslationToggle)
                allTranslations.filterKeys { it !in display.fields }.takeIf { it.isNotEmpty() }
            else null

            StudentVocabResponse(
                id = row[StudentVocabTable.id].value.toString(),
                studentId = row[StudentVocabTable.studentId].value.toString(),
                entryId = row[VocabEntryTable.id].value.toString(),
                lemma = row[VocabEntryTable.lemma],
                article = row[VocabEntryTable.article],
                plural = row[VocabEntryTable.plural],
                wordType = row[VocabEntryTable.wordType],
                forms = row[VocabEntryTable.forms],
                government = row[VocabEntryTable.government],
                exampleSentence = row[VocabEntryTable.exampleSentence],
                level = row[VocabEntryTable.level],
                topicIds = topicsByEntry[row[VocabEntryTable.id].value].orEmpty().map(UUID::toString),
                synonyms = row[VocabEntryTable.synonyms],
                lessonId = row[StudentVocabTable.lessonId]?.value?.toString(),
                status = row[StudentVocabTable.status],
                due = row[StudentVocabTable.due],
                reps = row[StudentVocabTable.reps],
                lapses = row[StudentVocabTable.lapses],
                lastReview = row[StudentVocabTable.lastReview],
                addedAt = row[StudentVocabTable.addedAt],
                display = display,
                translations = shownTranslations,
                explanationDe = if (showExplanation) row[VocabEntryTable.explanationDe] else null,
                revealTranslations = reveal,
            )
        }
    }

    /** Batch-loaded lookup tables for display-setting resolution. */
    private class DisplaySettingResolver(
        private val lessonOverrides: Map<UUID, VocabDisplaySetting>,
        private val pairSettings: Map<Pair<UUID, UUID>, VocabDisplaySetting>,
        private val levels: Map<UUID, LanguageLevel?>,
    ) {
        fun resolve(row: ResultRow): VocabDisplaySetting {
            val studentId = row[StudentVocabTable.studentId].value
            row[StudentVocabTable.lessonId]?.value?.let { lessonOverrides[it] }?.let { return it }
            pairSettings[row[VocabEntryTable.teacherId].value to studentId]?.let { return it }
            return VocabDisplay.defaultFor(levels[studentId])
        }

        companion object {
            fun load(rows: List<ResultRow>): DisplaySettingResolver {
                val lessonIds = rows.mapNotNull { it[StudentVocabTable.lessonId]?.value }.distinct()
                val studentIds = rows.map { it[StudentVocabTable.studentId].value }.distinct()
                val teacherIds = rows.map { it[VocabEntryTable.teacherId].value }.distinct()

                val lessonOverrides = if (lessonIds.isEmpty()) emptyMap() else LessonTable.selectAll()
                    .where { LessonTable.id inList lessonIds }
                    .mapNotNull { lesson ->
                        VocabDisplay.of(lesson[LessonTable.vocabDisplayFields], lesson[LessonTable.allowTranslationToggle])
                            ?.let { lesson[LessonTable.id].value to it }
                    }.toMap()

                val pairSettings = TeacherStudentTable.selectAll()
                    .where { (TeacherStudentTable.studentId inList studentIds) and (TeacherStudentTable.teacherId inList teacherIds) }
                    .mapNotNull { ts ->
                        VocabDisplay.of(ts[TeacherStudentTable.vocabDisplayFields], ts[TeacherStudentTable.allowTranslationToggle])
                            ?.let { (ts[TeacherStudentTable.teacherId].value to ts[TeacherStudentTable.studentId].value) to it }
                    }.toMap()

                val levels = UserTable.select(UserTable.id, UserTable.level)
                    .where { UserTable.id inList studentIds }
                    .associate { it[UserTable.id].value to it[UserTable.level] }

                return DisplaySettingResolver(lessonOverrides, pairSettings, levels)
            }
        }
    }

    companion object {
        private const val FSRS_LEARNING: Short = 1
        private const val FSRS_REVIEW: Short = 2
    }
}

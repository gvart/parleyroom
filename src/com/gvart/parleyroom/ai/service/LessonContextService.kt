package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.transfer.AttendeeRef
import com.gvart.parleyroom.ai.transfer.ContextSummary
import com.gvart.parleyroom.ai.transfer.DisplaySource
import com.gvart.parleyroom.ai.transfer.GrammarGaps
import com.gvart.parleyroom.ai.data.DraftMode
import com.gvart.parleyroom.ai.transfer.PastLessonRef
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.GoalType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.progress.data.GrammarProgressStatus
import com.gvart.parleyroom.progress.service.ProgressCalculator
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.topic.transfer.GrammarTopicRef
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.LessonVocabTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabDisplay
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime
import java.util.UUID

/** Which extra context a generation uses: past lesson notes (null = the latest one) and library focus. */
data class ContextSources(
    val pastLessonIds: List<UUID>? = null,
    val topicIds: List<UUID> = emptyList(),
    val grammarTopicIds: List<UUID> = emptyList(),
)

/** Notes of an earlier lesson, as plain text. */
data class PastNotes(val ref: PastLessonRef, val notes: String)

/**
 * Everything the server adds to Anna's prompt for a lesson (or, without [lessonId], for one
 * student). [attendees] (with names) is for our own API only; [toPromptText] and [pastNotesText]
 * render the model-facing part, which never contains names, e-mails or ids.
 */
data class LessonContext(
    val lessonId: UUID?,
    val teacherId: UUID,
    val mode: DraftMode,
    val groupId: UUID?,
    val level: LanguageLevel?,
    val display: VocabDisplaySetting,
    val displaySource: DisplaySource,
    val attendees: List<AttendeeRef>,
    val knownWordCount: Int,
    val knownWords: List<String>,
    val coveredGrammar: List<GrammarTopicRef>,
    val grammarGaps: GrammarGaps,
    val topicPaths: List<String>,
    val grammarTopics: List<GrammarTopicRef>,
    val prefillNotes: String?,
    val promptUsed: String?,
    val goals: List<String> = emptyList(),
    val weakWords: List<String> = emptyList(),
    val pastNotes: List<PastNotes> = emptyList(),
    val focusTopics: List<String> = emptyList(),
    val focusGrammar: List<String> = emptyList(),
) {
    val attendeeIds: List<UUID> get() = attendees.map { UUID.fromString(it.id) }

    fun summary() = ContextSummary(
        level = level,
        display = display,
        displaySource = displaySource,
        lessonOverrideActive = displaySource == DisplaySource.LESSON,
        knownWordCount = knownWordCount,
        coveredGrammar = coveredGrammar,
        grammarGaps = grammarGaps,
        libraryTopicCount = topicPaths.size,
        libraryGrammarTopicCount = grammarTopics.size,
        attendees = attendees,
        attendeeCount = attendees.size,
        goals = goals,
        weakWords = weakWords,
        pastLessons = pastNotes.map { it.ref },
        focusTopics = focusTopics,
        focusGrammarTopics = focusGrammar,
    )

    fun toPromptText(): String = buildString {
        appendLine("Lesson type: ${if (mode == DraftMode.CLUB) "club (group session, ${attendees.size} participants)" else "1:1 lesson"}")
        appendLine("Level: ${level ?: "unknown"}")
        val required = display.fields.map {
            when (it) {
                VocabDisplay.DE_EXPLANATION -> "explanationDe (German explanation)"
                else -> "translations.$it"
            }
        }
        appendLine("Vocabulary display setting: every word MUST have ${required.joinToString(" and ")}. " +
                "Other translations are optional but welcome.")
        appendLine()
        appendLine(if (mode == DraftMode.CLUB) "Words the group already had:" else "Words the student already knows:")
        appendLine(knownWords.joinToString(", ").ifEmpty { "(none yet)" })
        if (mode == DraftMode.ONE_ON_ONE) {
            appendLine()
            appendLine("Words the student keeps forgetting (repeat or practise them when it fits):")
            appendLine(weakWords.joinToString(", ").ifEmpty { "(none known)" })
            appendLine()
            appendLine("The student's goals:")
            appendLine(goals.joinToString("; ").ifEmpty { "(none set)" })
        }
        if (focusTopics.isNotEmpty() || focusGrammar.isNotEmpty()) {
            appendLine()
            appendLine("Focus the teacher chose for this generation (prefer these topics and grammar):")
            if (focusTopics.isNotEmpty()) appendLine("Topics: ${focusTopics.joinToString(", ")}")
            if (focusGrammar.isNotEmpty()) appendLine("Grammar: ${focusGrammar.joinToString(", ")}")
        }
        appendLine()
        appendLine("Grammar topics already covered:")
        appendLine(coveredGrammar.joinToString(", ") { refText(it) }.ifEmpty { "(none yet)" })
        appendLine()
        appendLine(
            if (mode == DraftMode.CLUB) "Grammar the group still needs to work on (weak homework results for at least half of the participants):"
            else "Grammar the student still needs to work on (weak homework results):"
        )
        appendLine(grammarGaps.needsWork.joinToString(", ").ifEmpty { "(none known)" })
        appendLine()
        appendLine("Grammar of level ${level ?: "unknown"} not covered yet${if (mode == DraftMode.CLUB) " (for at least half of the participants)" else ""}:")
        appendLine(grammarGaps.notCovered.joinToString(", ").ifEmpty { "(none known)" })
        appendLine()
        appendLine("Library topics (reuse these exact names for topicName and suggestedTopics when they fit):")
        appendLine(topicPaths.joinToString("\n").ifEmpty { "(empty library)" })
        appendLine()
        appendLine("Library grammar topics (reuse these exact names in suggestedGrammarTopics when they fit):")
        append(grammarTopics.joinToString(", ") { refText(it) }.ifEmpty { "(empty library)" })
    }

    /** Earlier lessons' notes, oldest first, headed by their date only. */
    fun pastNotesText(): String = pastNotes.sortedBy { it.ref.scheduledAt }.joinToString("\n\n") {
        "Lesson on ${it.ref.scheduledAt.toLocalDate()}:\n${it.notes}"
    }

    private fun refText(ref: GrammarTopicRef) = ref.level?.let { "${ref.name} ($it)" } ?: ref.name
}

/** Builds [LessonContext]. Must run in a transaction. */
class LessonContextService(private val progress: ProgressCalculator) {

    fun requireLessonTeacher(lessonId: UUID, principal: UserPrincipal): ResultRow {
        val lesson = LessonTable.findByIdOrThrow(lessonId, "Lesson")
        AuthorizationHelper.requireOwnerOrAdmin(lesson[LessonTable.teacherId].value, principal, "Only the lesson's teacher can do this")
        return lesson
    }

    fun modeOf(lesson: ResultRow): DraftMode =
        if (lesson[LessonTable.groupId] != null || lesson[LessonTable.type] != LessonType.ONE_ON_ONE) DraftMode.CLUB
        else DraftMode.ONE_ON_ONE

    fun load(lesson: ResultRow, sources: ContextSources = ContextSources()): LessonContext {
        val lessonId = lesson[LessonTable.id].value
        val teacherId = lesson[LessonTable.teacherId].value
        val groupId = lesson[LessonTable.groupId]?.value
        val mode = modeOf(lesson)

        val attendees = (LessonStudentTable innerJoin UserTable)
            .select(UserTable.id, UserTable.firstName, UserTable.lastName, UserTable.level)
            .where { (LessonStudentTable.lessonId eq lessonId) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) }
            .orderBy(UserTable.lastName)
            .toList()
        val attendeeRefs = attendees.map { AttendeeRef(it[UserTable.id].value.toString(), it[UserTable.firstName], it[UserTable.lastName]) }
        val attendeeIds = attendees.map { it[UserTable.id].value }
        val student = attendeeIds.singleOrNull()?.takeIf { mode == DraftMode.ONE_ON_ONE }

        val level = when (mode) {
            DraftMode.ONE_ON_ONE -> attendees.singleOrNull()?.get(UserTable.level)
            DraftMode.CLUB -> groupId?.let { GroupTable.findByIdOrThrow(it, "Group")[GroupTable.level] }
        } ?: lesson[LessonTable.level]

        val lessonOverride = VocabDisplay.of(lesson[LessonTable.vocabDisplayFields], lesson[LessonTable.allowTranslationToggle])
        val (display, source) = when {
            lessonOverride != null -> lessonOverride to DisplaySource.LESSON
            else -> studentOrLevelDisplay(teacherId, student, level)
        }

        val known = knownWords(mode, teacherId, lessonId, groupId, student, attendeeIds)
        val earlier = earlierLessons(mode, teacherId, lesson[LessonTable.scheduledAt], lessonId, student, groupId, lesson[LessonTable.type])

        return LessonContext(
            lessonId = lessonId,
            teacherId = teacherId,
            mode = mode,
            groupId = groupId,
            level = level,
            display = display,
            displaySource = source,
            attendees = attendeeRefs,
            knownWordCount = known.first,
            knownWords = known.second,
            coveredGrammar = coveredGrammar(earlier),
            grammarGaps = grammarGaps(teacherId, level, attendeeIds),
            topicPaths = topicPaths(teacherId),
            grammarTopics = libraryGrammar(teacherId),
            prefillNotes = lesson[LessonTable.rawNotes],
            promptUsed = lesson[LessonTable.promptUsed],
            goals = student?.let { goals(teacherId, it) }.orEmpty(),
            weakWords = student?.let { weakWords(teacherId, it) }.orEmpty(),
            pastNotes = pastNotes(earlier, sources.pastLessonIds),
            focusTopics = focusTopics(sources),
            focusGrammar = focusGrammar(sources),
        )
    }

    /** Out-of-lesson context for one of the teacher's students (always 1:1). */
    fun loadForStudent(teacherId: UUID, studentId: UUID, sources: ContextSources = ContextSources()): LessonContext {
        val user = UserTable.findByIdOrThrow(studentId, "Student")
        val level = user[UserTable.level]
        val (display, source) = studentOrLevelDisplay(teacherId, studentId, level)
        val known = knownWords(DraftMode.ONE_ON_ONE, teacherId, null, null, studentId, listOf(studentId))
        val earlier = earlierLessons(DraftMode.ONE_ON_ONE, teacherId, OffsetDateTime.now(), null, studentId, null, LessonType.ONE_ON_ONE)
        return LessonContext(
            lessonId = null,
            teacherId = teacherId,
            mode = DraftMode.ONE_ON_ONE,
            groupId = null,
            level = level,
            display = display,
            displaySource = source,
            attendees = listOf(AttendeeRef(studentId.toString(), user[UserTable.firstName], user[UserTable.lastName])),
            knownWordCount = known.first,
            knownWords = known.second,
            coveredGrammar = coveredGrammar(earlier),
            grammarGaps = grammarGaps(teacherId, level, listOf(studentId)),
            topicPaths = topicPaths(teacherId),
            grammarTopics = libraryGrammar(teacherId),
            prefillNotes = null,
            promptUsed = null,
            goals = goals(teacherId, studentId),
            weakWords = weakWords(teacherId, studentId),
            pastNotes = pastNotes(earlier, sources.pastLessonIds),
            focusTopics = focusTopics(sources),
            focusGrammar = focusGrammar(sources),
        )
    }

    /** Earlier lessons with notes for the "past lessons" picker, newest first. */
    fun pastLessonChoices(ctx: LessonContext, lesson: ResultRow?): List<PastLessonRef> {
        val earlier = if (lesson != null)
            earlierLessons(ctx.mode, ctx.teacherId, lesson[LessonTable.scheduledAt], ctx.lessonId, ctx.attendeeIds.singleOrNull()
                ?.takeIf { ctx.mode == DraftMode.ONE_ON_ONE }, ctx.groupId, lesson[LessonTable.type])
        else earlierLessons(DraftMode.ONE_ON_ONE, ctx.teacherId, OffsetDateTime.now(), null, ctx.attendeeIds.single(), null, LessonType.ONE_ON_ONE)
        return withNotes(earlier).take(MAX_PAST_LESSON_CHOICES).map { it.ref }
    }

    private fun studentOrLevelDisplay(teacherId: UUID, student: UUID?, level: LanguageLevel?): Pair<VocabDisplaySetting, DisplaySource> {
        val studentSetting = student?.let { studentId ->
            TeacherStudentTable.selectAll()
                .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId eq studentId) }
                .singleOrNull()
                ?.let { VocabDisplay.of(it[TeacherStudentTable.vocabDisplayFields], it[TeacherStudentTable.allowTranslationToggle]) }
        }
        return if (studentSetting != null) studentSetting to DisplaySource.STUDENT
        else VocabDisplay.defaultFor(level) to DisplaySource.LEVEL_DEFAULT
    }

    private fun libraryGrammar(teacherId: UUID) = GrammarTopicTable.selectAll()
        .where { GrammarTopicTable.teacherId eq teacherId }
        .orderBy(GrammarTopicTable.level to SortOrder.ASC_NULLS_LAST, GrammarTopicTable.name to SortOrder.ASC)
        .limit(MAX_LIBRARY_ITEMS)
        .map { GrammarTopicRef(it[GrammarTopicTable.id].value.toString(), it[GrammarTopicTable.name], it[GrammarTopicTable.level]) }

    /** Earlier lessons (ids) with non-blank notes, newest first. */
    private fun withNotes(lessonIds: List<UUID>): List<PastNotes> {
        if (lessonIds.isEmpty()) return emptyList()
        return LessonTable.select(LessonTable.id, LessonTable.title, LessonTable.scheduledAt, LessonTable.rawNotes)
            .where { LessonTable.id inList lessonIds }
            .orderBy(LessonTable.scheduledAt, SortOrder.DESC)
            .mapNotNull { row ->
                val notes = row[LessonTable.rawNotes]?.let(HtmlText::toPlainText)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PastNotes(PastLessonRef(row[LessonTable.id].value.toString(), row[LessonTable.title], row[LessonTable.scheduledAt], notes.take(PREVIEW)), notes)
            }
    }

    /** [selected] = null: the latest earlier lesson with notes. Selected ids must be earlier lessons of the same learner(s). */
    private fun pastNotes(earlier: List<UUID>, selected: List<UUID>?): List<PastNotes> {
        if (selected == null) return withNotes(earlier).take(1)
        val unknown = selected.filter { it !in earlier }
        if (unknown.isNotEmpty())
            throw BadRequestException("Not an earlier completed lesson of this student or group: ${unknown.joinToString()}", code = "AI_DRAFT_PAST_LESSON_INVALID")
        return withNotes(selected.distinct())
    }

    private fun focusTopics(sources: ContextSources): List<String> =
        LibraryAccess.topicRefs(sources.topicIds).let { refs -> sources.topicIds.mapNotNull { refs[it]?.name } }

    private fun focusGrammar(sources: ContextSources): List<String> =
        LibraryAccess.grammarTopicRefs(sources.grammarTopicIds).let { refs -> sources.grammarTopicIds.mapNotNull { refs[it]?.name } }

    /** Active goals, without the free-text note (it may name people). */
    private fun goals(teacherId: UUID, studentId: UUID): List<String> = GoalTable.selectAll()
        .where { (GoalTable.studentId eq studentId) and (GoalTable.teacherId eq teacherId) and (GoalTable.status eq GoalStatus.ACTIVE) }
        .orderBy(GoalTable.createdAt)
        .map { row ->
            val by = row[GoalTable.targetDate]?.let { " by $it" }.orEmpty()
            when (row[GoalTable.type]) {
                GoalType.EXAM -> "Exam ${row[GoalTable.examName].orEmpty()} (${row[GoalTable.targetLevel]})$by".replace("  ", " ")
                GoalType.LEVEL -> "Reach level ${row[GoalTable.targetLevel]}$by"
            }
        }

    /** The student's words with FSRS lapses or a high difficulty, worst first. */
    private fun weakWords(teacherId: UUID, studentId: UUID): List<String> = (StudentVocabTable innerJoin VocabEntryTable)
        .select(VocabEntryTable.article, VocabEntryTable.lemma, StudentVocabTable.lapses, StudentVocabTable.difficulty)
        .where {
            (StudentVocabTable.studentId eq studentId) and (VocabEntryTable.teacherId eq teacherId) and
                    ((StudentVocabTable.lapses greaterEq 1) or (StudentVocabTable.difficulty greaterEq WEAK_DIFFICULTY))
        }
        .orderBy(StudentVocabTable.lapses to SortOrder.DESC, StudentVocabTable.difficulty to SortOrder.DESC_NULLS_LAST)
        .limit(MAX_WEAK_WORDS)
        .map { row -> listOfNotNull(row[VocabEntryTable.article]?.name?.lowercase(), row[VocabEntryTable.lemma]).joinToString(" ") }

    /** (total count, newest [MAX_KNOWN_WORDS] words rendered as "die Gießkanne"). */
    private fun knownWords(
        mode: DraftMode,
        teacherId: UUID,
        lessonId: UUID?,
        groupId: UUID?,
        student: UUID?,
        attendeeIds: List<UUID>,
    ): Pair<Int, List<String>> {
        val entryIds: List<UUID> = when {
            mode == DraftMode.ONE_ON_ONE && student != null -> (StudentVocabTable innerJoin VocabEntryTable)
                .select(StudentVocabTable.vocabEntryId)
                .where { (StudentVocabTable.studentId eq student) and (VocabEntryTable.teacherId eq teacherId) }
                .orderBy(StudentVocabTable.addedAt, SortOrder.DESC)
                .map { it[StudentVocabTable.vocabEntryId].value }
            mode == DraftMode.CLUB && groupId != null -> LessonVocabTable
                .select(LessonVocabTable.vocabEntryId)
                .where {
                    (LessonVocabTable.lessonId neq lessonId!!) and (LessonVocabTable.lessonId inSubQuery
                            LessonTable.select(LessonTable.id).where { LessonTable.groupId eq groupId })
                }
                .map { it[LessonVocabTable.vocabEntryId].value }
                .distinct()
            mode == DraftMode.CLUB && attendeeIds.isNotEmpty() -> {
                // Without a group: the words every attendee already has.
                (StudentVocabTable innerJoin VocabEntryTable)
                    .select(StudentVocabTable.studentId, StudentVocabTable.vocabEntryId)
                    .where { (StudentVocabTable.studentId inList attendeeIds) and (VocabEntryTable.teacherId eq teacherId) }
                    .groupBy({ it[StudentVocabTable.vocabEntryId].value }) { it[StudentVocabTable.studentId].value }
                    .filterValues { it.toSet().size == attendeeIds.size }
                    .keys.toList()
            }
            else -> emptyList()
        }
        if (entryIds.isEmpty()) return 0 to emptyList()
        val shown = entryIds.take(MAX_KNOWN_WORDS)
        val rows = VocabEntryTable.selectAll().where { VocabEntryTable.id inList shown }
            .associateBy { it[VocabEntryTable.id].value }
        val words = shown.mapNotNull { id ->
            rows[id]?.let { row -> listOfNotNull(row[VocabEntryTable.article]?.name?.lowercase(), row[VocabEntryTable.lemma]).joinToString(" ") }
        }
        return entryIds.size to words
    }

    /**
     * Earlier lessons that actually took place (COMPLETED; not requested, cancelled or still running) of the same learner(s): the student's, the group's, or (club
     * without group) the teacher's of the same type.
     */
    private fun earlierLessons(
        mode: DraftMode,
        teacherId: UUID,
        before: OffsetDateTime,
        excludeLessonId: UUID?,
        student: UUID?,
        groupId: UUID?,
        type: LessonType,
    ): List<UUID> {
        val query = LessonTable.select(LessonTable.id).where {
            (LessonTable.teacherId eq teacherId) and
                    (LessonTable.scheduledAt less before) and
                    (LessonTable.status eq LessonStatus.COMPLETED)
        }
        excludeLessonId?.let { id -> query.andWhere { LessonTable.id neq id } }
        when {
            mode == DraftMode.ONE_ON_ONE && student != null -> query.andWhere {
                LessonTable.id inSubQuery LessonStudentTable.select(LessonStudentTable.lessonId).where {
                    (LessonStudentTable.studentId eq student) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                }
            }
            mode == DraftMode.ONE_ON_ONE -> return emptyList()
            groupId != null -> query.andWhere { LessonTable.groupId eq groupId }
            else -> query.andWhere { LessonTable.type eq type }
        }
        return query.map { it[LessonTable.id].value }
    }

    private fun coveredGrammar(lessonIds: List<UUID>): List<GrammarTopicRef> {
        if (lessonIds.isEmpty()) return emptyList()
        return (LessonGrammarTopicTable innerJoin GrammarTopicTable)
            .select(GrammarTopicTable.id, GrammarTopicTable.name, GrammarTopicTable.level)
            .where { LessonGrammarTopicTable.lessonId inList lessonIds }
            .withDistinct()
            .orderBy(GrammarTopicTable.name)
            .limit(MAX_LIBRARY_ITEMS)
            .map { GrammarTopicRef(it[GrammarTopicTable.id].value.toString(), it[GrammarTopicTable.name], it[GrammarTopicTable.level]) }
    }

    /**
     * Effective progress status (P8) of the teacher's grammar topics at [level]: a topic is a gap when
     * at least half of the attendees (1:1: the student) have that status. Checklist order, lists capped.
     */
    private fun grammarGaps(teacherId: UUID, level: LanguageLevel?, attendeeIds: List<UUID>): GrammarGaps {
        if (level == null || attendeeIds.isEmpty()) return GrammarGaps.NONE
        val topics = progress.grammarTopics(teacherId, listOf(level))
        val evaluations = progress.grammar(teacherId, attendeeIds, topics)
        val quorum = (attendeeIds.size + 1) / 2
        fun gaps(status: GrammarProgressStatus) = topics.indices
            .filter { i -> attendeeIds.count { evaluations.getValue(it)[i].effective == status } >= quorum }
            .map { topics[it].name }
        val needsWork = gaps(GrammarProgressStatus.NEEDS_WORK)
        val notCovered = gaps(GrammarProgressStatus.NOT_COVERED)
        return GrammarGaps(needsWork.take(MAX_GAPS), notCovered.take(MAX_GAPS), needsWork.size, notCovered.size)
    }

    /** The teacher's topic tree as "Alltag > Haushalt" paths, sorted. */
    fun topicPaths(teacherId: UUID): List<String> {
        val topics = TopicTable.selectAll().where { TopicTable.teacherId eq teacherId }
            .associate { it[TopicTable.id].value to (it[TopicTable.parentId]?.value to it[TopicTable.name]) }
        fun path(id: UUID, depth: Int = 0): String {
            val (parent, name) = topics.getValue(id)
            return if (parent == null || parent !in topics || depth > 10) name else path(parent, depth + 1) + " > " + name
        }
        return topics.keys.map { path(it) }.sorted().take(MAX_LIBRARY_ITEMS)
    }

    companion object {
        const val MAX_KNOWN_WORDS = 300
        const val MAX_LIBRARY_ITEMS = 300
        const val MAX_GAPS = 30
        const val MAX_WEAK_WORDS = 30
        const val MAX_PAST_LESSON_CHOICES = 20
        /** FSRS difficulty runs 1..10. */
        const val WEAK_DIFFICULTY = 7.0
        private const val PREVIEW = 300
    }
}

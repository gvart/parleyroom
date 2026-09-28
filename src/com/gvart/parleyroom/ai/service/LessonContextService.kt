package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.transfer.AttendeeRef
import com.gvart.parleyroom.ai.transfer.ContextSummary
import com.gvart.parleyroom.ai.transfer.DisplaySource
import com.gvart.parleyroom.ai.transfer.NachbereitungMode
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonDocumentTable
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * Everything the server adds to Anna's prompt for a lesson. [attendees] (with names) is for our
 * own API only; [toPromptText] renders the model-facing part, which never contains names, e-mails
 * or ids.
 */
data class LessonContext(
    val lessonId: UUID,
    val teacherId: UUID,
    val mode: NachbereitungMode,
    val groupId: UUID?,
    val level: LanguageLevel?,
    val display: VocabDisplaySetting,
    val displaySource: DisplaySource,
    val attendees: List<AttendeeRef>,
    val knownWordCount: Int,
    val knownWords: List<String>,
    val coveredGrammar: List<GrammarTopicRef>,
    val topicPaths: List<String>,
    val grammarTopics: List<GrammarTopicRef>,
    val prefillNotes: String?,
    val promptUsed: String?,
) {
    val attendeeIds: List<UUID> get() = attendees.map { UUID.fromString(it.id) }

    fun summary() = ContextSummary(
        level = level,
        display = display,
        displaySource = displaySource,
        lessonOverrideActive = displaySource == DisplaySource.LESSON,
        knownWordCount = knownWordCount,
        coveredGrammar = coveredGrammar,
        libraryTopicCount = topicPaths.size,
        libraryGrammarTopicCount = grammarTopics.size,
        attendees = attendees,
        attendeeCount = attendees.size,
    )

    fun toPromptText(): String = buildString {
        appendLine("Lesson type: ${if (mode == NachbereitungMode.CLUB) "club (group session, ${attendees.size} participants)" else "1:1 lesson"}")
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
        appendLine(if (mode == NachbereitungMode.CLUB) "Words the group already had:" else "Words the student already knows:")
        appendLine(knownWords.joinToString(", ").ifEmpty { "(none yet)" })
        appendLine()
        appendLine("Grammar topics already covered:")
        appendLine(coveredGrammar.joinToString(", ") { refText(it) }.ifEmpty { "(none yet)" })
        appendLine()
        appendLine("Library topics (reuse these exact names for topicName and suggestedTopics when they fit):")
        appendLine(topicPaths.joinToString("\n").ifEmpty { "(empty library)" })
        appendLine()
        appendLine("Library grammar topics (reuse these exact names in suggestedGrammarTopics when they fit):")
        append(grammarTopics.joinToString(", ") { refText(it) }.ifEmpty { "(empty library)" })
    }

    private fun refText(ref: GrammarTopicRef) = ref.level?.let { "${ref.name} ($it)" } ?: ref.name
}

/** Builds [LessonContext]. Must run in a transaction. */
class LessonContextService {

    fun requireLessonTeacher(lessonId: UUID, principal: UserPrincipal): ResultRow {
        val lesson = LessonTable.findByIdOrThrow(lessonId, "Lesson")
        AuthorizationHelper.requireOwnerOrAdmin(lesson[LessonTable.teacherId].value, principal, "Only the lesson's teacher can do this")
        return lesson
    }

    fun load(lesson: ResultRow): LessonContext {
        val lessonId = lesson[LessonTable.id].value
        val teacherId = lesson[LessonTable.teacherId].value
        val groupId = lesson[LessonTable.groupId]?.value
        val mode = if (groupId != null || lesson[LessonTable.type] != LessonType.ONE_ON_ONE) NachbereitungMode.CLUB
        else NachbereitungMode.ONE_ON_ONE

        val attendees = (LessonStudentTable innerJoin UserTable)
            .select(UserTable.id, UserTable.firstName, UserTable.lastName, UserTable.level)
            .where { (LessonStudentTable.lessonId eq lessonId) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) }
            .orderBy(UserTable.lastName)
            .toList()
        val attendeeRefs = attendees.map { AttendeeRef(it[UserTable.id].value.toString(), it[UserTable.firstName], it[UserTable.lastName]) }
        val attendeeIds = attendees.map { it[UserTable.id].value }
        val student = attendeeIds.singleOrNull()?.takeIf { mode == NachbereitungMode.ONE_ON_ONE }

        val level = when (mode) {
            NachbereitungMode.ONE_ON_ONE -> attendees.singleOrNull()?.get(UserTable.level)
            NachbereitungMode.CLUB -> groupId?.let { GroupTable.findByIdOrThrow(it, "Group")[GroupTable.level] }
        } ?: lesson[LessonTable.level]

        val lessonOverride = VocabDisplay.of(lesson[LessonTable.vocabDisplayFields], lesson[LessonTable.allowTranslationToggle])
        val studentSetting = student?.let { studentId ->
            TeacherStudentTable.selectAll()
                .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId eq studentId) }
                .singleOrNull()
                ?.let { VocabDisplay.of(it[TeacherStudentTable.vocabDisplayFields], it[TeacherStudentTable.allowTranslationToggle]) }
        }
        val (display, source) = when {
            lessonOverride != null -> lessonOverride to DisplaySource.LESSON
            studentSetting != null -> studentSetting to DisplaySource.STUDENT
            else -> VocabDisplay.defaultFor(level) to DisplaySource.LEVEL_DEFAULT
        }

        val known = knownWords(mode, teacherId, lessonId, groupId, student, attendeeIds)
        val earlierLessons = earlierLessons(mode, lesson, student)

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
            coveredGrammar = coveredGrammar(earlierLessons),
            topicPaths = topicPaths(teacherId),
            grammarTopics = GrammarTopicTable.selectAll()
                .where { GrammarTopicTable.teacherId eq teacherId }
                .orderBy(GrammarTopicTable.level to SortOrder.ASC_NULLS_LAST, GrammarTopicTable.name to SortOrder.ASC)
                .limit(MAX_LIBRARY_ITEMS)
                .map { GrammarTopicRef(it[GrammarTopicTable.id].value.toString(), it[GrammarTopicTable.name], it[GrammarTopicTable.level]) },
            prefillNotes = lesson[LessonTable.rawNotes] ?: LessonDocumentTable.select(LessonDocumentTable.teacherNotes)
                .where { LessonDocumentTable.lessonId eq lessonId }
                .singleOrNull()?.get(LessonDocumentTable.teacherNotes),
            promptUsed = lesson[LessonTable.promptUsed],
        )
    }

    /** (total count, newest [MAX_KNOWN_WORDS] words rendered as "die Gießkanne"). */
    private fun knownWords(
        mode: NachbereitungMode,
        teacherId: UUID,
        lessonId: UUID,
        groupId: UUID?,
        student: UUID?,
        attendeeIds: List<UUID>,
    ): Pair<Int, List<String>> {
        val entryIds: List<UUID> = when {
            mode == NachbereitungMode.ONE_ON_ONE && student != null -> (StudentVocabTable innerJoin VocabEntryTable)
                .select(StudentVocabTable.vocabEntryId)
                .where { (StudentVocabTable.studentId eq student) and (VocabEntryTable.teacherId eq teacherId) }
                .orderBy(StudentVocabTable.addedAt, SortOrder.DESC)
                .map { it[StudentVocabTable.vocabEntryId].value }
            mode == NachbereitungMode.CLUB && groupId != null -> LessonVocabTable
                .select(LessonVocabTable.vocabEntryId)
                .where {
                    (LessonVocabTable.lessonId neq lessonId) and (LessonVocabTable.lessonId inSubQuery
                            LessonTable.select(LessonTable.id).where { LessonTable.groupId eq groupId })
                }
                .map { it[LessonVocabTable.vocabEntryId].value }
                .distinct()
            mode == NachbereitungMode.CLUB && attendeeIds.isNotEmpty() -> {
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

    /** Earlier lessons of the same learner(s): the student's, the group's, or (club without group) the teacher's of the same type. */
    private fun earlierLessons(mode: NachbereitungMode, lesson: ResultRow, student: UUID?): List<UUID> {
        val query = LessonTable.select(LessonTable.id).where {
            (LessonTable.teacherId eq lesson[LessonTable.teacherId]) and
                    (LessonTable.id neq lesson[LessonTable.id]) and
                    (LessonTable.scheduledAt less lesson[LessonTable.scheduledAt])
        }
        val groupId = lesson[LessonTable.groupId]
        when {
            mode == NachbereitungMode.ONE_ON_ONE && student != null -> query.andWhere {
                LessonTable.id inSubQuery LessonStudentTable.select(LessonStudentTable.lessonId).where {
                    (LessonStudentTable.studentId eq student) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                }
            }
            mode == NachbereitungMode.ONE_ON_ONE -> return emptyList()
            groupId != null -> query.andWhere { LessonTable.groupId eq groupId }
            else -> query.andWhere { LessonTable.type eq lesson[LessonTable.type] }
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

    /** The teacher's topic tree as "Alltag > Haushalt" paths, sorted. */
    private fun topicPaths(teacherId: UUID): List<String> {
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
    }
}

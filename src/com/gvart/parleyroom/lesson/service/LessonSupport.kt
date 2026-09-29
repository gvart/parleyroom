package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.service.singleOrNotFound
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentLessonTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.transfer.LessonDocumentRef
import com.gvart.parleyroom.lesson.data.LessonCorrectionTable
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.lesson.data.LessonEventTable
import com.gvart.parleyroom.lesson.data.LessonEventType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.CorrectedSentenceResponse
import com.gvart.parleyroom.lesson.transfer.LessonVocabRef
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.LessonStudentResponse
import com.gvart.parleyroom.lesson.transfer.LessonTeacherResponse
import com.gvart.parleyroom.lesson.transfer.PendingRescheduleResponse
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.LessonVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabDisplay
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.GreaterOp
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime
import java.util.UUID

class LessonSupport {

    fun findLesson(lessonId: UUID): ResultRow =
        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .singleOrNotFound("Lesson")

    fun findLessonForUpdate(lessonId: UUID): ResultRow =
        LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .forUpdate()
            .singleOrNotFound("Lesson")

    fun findPendingReschedule(lessonId: UUID): ResultRow =
        LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId eq lessonId) and
                        (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                        (LessonEventTable.resolved eq false)
            }
            .singleOrNull() ?: throw NotFoundException("No pending reschedule found", code = "RESCHEDULE_NOT_FOUND")

    fun findStudentEntry(lessonId: UUID, studentId: UUID): ResultRow =
        LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq studentId)
            }
            .singleOrNull() ?: throw NotFoundException("Student not found in this lesson", code = "STUDENT_NOT_IN_LESSON")

    fun requireLessonParticipant(lessonId: UUID, lesson: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return

        val isTeacher = lesson[LessonTable.teacherId].value == principal.id
        val isStudent = LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq principal.id) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .empty().not()

        if (!isTeacher && !isStudent)
            throw ForbiddenException("Only participants of this lesson can perform this action")
    }

    fun checkTeacherOverlap(
        teacherId: UUID,
        scheduledAt: OffsetDateTime,
        durationMinutes: Int,
        excludeLessonId: UUID? = null,
        bufferMinutes: Int = 0,
    ) {
        // Hard overlap first (buffer=0). A real collision takes precedence over a
        // buffer-zone conflict so the error code reflects the actual cause.
        if (hasOverlap(teacherId, scheduledAt, durationMinutes, excludeLessonId, bufferMinutes = 0)) {
            throw ConflictException(
                "Teacher already has a lesson scheduled during this time",
                code = "AVAILABILITY_OVERLAP",
            )
        }
        if (bufferMinutes > 0 &&
            hasOverlap(teacherId, scheduledAt, durationMinutes, excludeLessonId, bufferMinutes)
        ) {
            throw ConflictException(
                "Booking is too close to another lesson (buffer $bufferMinutes min)",
                code = "AVAILABILITY_BUFFER_CONFLICT",
            )
        }
    }

    private fun hasOverlap(
        teacherId: UUID,
        scheduledAt: OffsetDateTime,
        durationMinutes: Int,
        excludeLessonId: UUID?,
        bufferMinutes: Int,
    ): Boolean {
        val newStart = scheduledAt.minusMinutes(bufferMinutes.toLong())
        val newEnd = scheduledAt.plusMinutes(durationMinutes.toLong()).plusMinutes(bufferMinutes.toLong())

        val existingLessonEnd = object : Expression<OffsetDateTime>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                LessonTable.scheduledAt.toQueryBuilder(queryBuilder)
                queryBuilder.append(" + (")
                LessonTable.durationMinutes.toQueryBuilder(queryBuilder)
                queryBuilder.append(" * INTERVAL '1 minute')")
            }
        }

        val newStartParam = QueryParameter(newStart, LessonTable.scheduledAt.columnType)

        val query = LessonTable.selectAll().where {
            (LessonTable.teacherId eq teacherId) and
                    (LessonTable.status neq LessonStatus.CANCELLED) and
                    (LessonTable.scheduledAt less newEnd) and
                    GreaterOp(existingLessonEnd, newStartParam)
        }

        if (excludeLessonId != null) {
            query.andWhere { LessonTable.id neq excludeLessonId }
        }

        return !query.empty()
    }

    fun getStudentIds(lessonId: UUID): List<UUID> =
        LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .map { it[LessonStudentTable.studentId].value }

    fun getOtherParticipants(lessonId: UUID, lesson: ResultRow, excludeUserId: UUID): List<UUID> {
        val teacherId = lesson[LessonTable.teacherId].value
        val studentIds = getStudentIds(lessonId)
        return (studentIds + teacherId).filter { it != excludeUserId }
    }

    fun toResponse(row: ResultRow, viewer: UserPrincipal): LessonResponse = toResponses(listOf(row), viewer).single()

    /**
     * Single place lesson responses are built. [viewer] decides teacher-only fields:
     * raw notes and the AI prompt are never returned to students, on any endpoint.
     */
    fun toResponses(rows: List<ResultRow>, viewer: UserPrincipal): List<LessonResponse> {
        if (rows.isEmpty()) return emptyList()
        val lessonIds = rows.map { it[LessonTable.id].value }

        val studentsByLesson: Map<UUID, List<LessonStudentResponse>> = LessonStudentTable
            .join(UserTable, JoinType.INNER, LessonStudentTable.studentId, UserTable.id)
            .selectAll()
            .where { LessonStudentTable.lessonId inList lessonIds }
            .groupBy({ it[LessonStudentTable.lessonId].value }) { studentRow ->
                LessonStudentResponse(
                    id = studentRow[UserTable.id].value.toString(),
                    firstName = studentRow[UserTable.firstName],
                    lastName = studentRow[UserTable.lastName],
                    status = studentRow[LessonStudentTable.status].name,
                )
            }

        val teachersById: Map<UUID, LessonTeacherResponse> = UserTable.selectAll()
            .where { UserTable.id inList rows.map { it[LessonTable.teacherId].value }.distinct() }
            .associate { teacherRow ->
                teacherRow[UserTable.id].value to LessonTeacherResponse(
                    id = teacherRow[UserTable.id].value.toString(),
                    firstName = teacherRow[UserTable.firstName],
                    lastName = teacherRow[UserTable.lastName],
                )
            }

        val pendingByLesson: Map<UUID, PendingRescheduleResponse> = LessonEventTable.selectAll()
            .where {
                (LessonEventTable.lessonId inList lessonIds) and
                        (LessonEventTable.eventType eq LessonEventType.RESCHEDULE_REQUESTED) and
                        (LessonEventTable.resolved eq false)
            }
            .associate { ev ->
                ev[LessonEventTable.lessonId].value to PendingRescheduleResponse(
                    newScheduledAt = ev[LessonEventTable.newScheduledAt]!!,
                    note = ev[LessonEventTable.note],
                    requestedBy = ev[LessonEventTable.actorId].value.toString(),
                )
            }

        val topicIdsByLesson = LessonTopicTable.selectAll()
            .where { LessonTopicTable.lessonId inList lessonIds }
            .groupBy({ it[LessonTopicTable.lessonId].value }) { it[LessonTopicTable.topicId].value }
        val topicRefs = LibraryAccess.topicRefs(topicIdsByLesson.values.flatten())

        val grammarIdsByLesson = LessonGrammarTopicTable.selectAll()
            .where { LessonGrammarTopicTable.lessonId inList lessonIds }
            .groupBy({ it[LessonGrammarTopicTable.lessonId].value }) { it[LessonGrammarTopicTable.grammarTopicId].value }
        val grammarRefs = LibraryAccess.grammarTopicRefs(grammarIdsByLesson.values.flatten())

        val vocabByLesson = LessonVocabTable
            .join(VocabEntryTable, JoinType.INNER, LessonVocabTable.vocabEntryId, VocabEntryTable.id)
            .selectAll()
            .where { LessonVocabTable.lessonId inList lessonIds }
            .orderBy(LessonVocabTable.orderIndex)
            .groupBy({ it[LessonVocabTable.lessonId].value }) {
                LessonVocabRef(
                    id = it[VocabEntryTable.id].value.toString(),
                    lemma = it[VocabEntryTable.lemma],
                    article = it[VocabEntryTable.article],
                    plural = it[VocabEntryTable.plural],
                    wordType = it[VocabEntryTable.wordType],
                )
            }

        val correctionsByLesson = LessonCorrectionTable.selectAll()
            .where { LessonCorrectionTable.lessonId inList lessonIds }
            .orderBy(LessonCorrectionTable.orderIndex)
            .groupBy({ it[LessonCorrectionTable.lessonId].value }) {
                CorrectedSentenceResponse(
                    id = it[LessonCorrectionTable.id].value.toString(),
                    incorrect = it[LessonCorrectionTable.incorrect],
                    correct = it[LessonCorrectionTable.correct],
                )
            }

        val documentsByLesson = DocumentLessonTable
            .join(DocumentTable, JoinType.INNER, DocumentLessonTable.documentId, DocumentTable.id)
            .select(DocumentLessonTable.lessonId, DocumentTable.id, DocumentTable.title, DocumentTable.revision, DocumentTable.updatedAt)
            .where { DocumentLessonTable.lessonId inList lessonIds }
            .orderBy(DocumentLessonTable.linkedAt)
            .groupBy({ it[DocumentLessonTable.lessonId].value }) {
                LessonDocumentRef(
                    id = it[DocumentTable.id].value.toString(),
                    title = it[DocumentTable.title],
                    revision = it[DocumentTable.revision],
                    updatedAt = it[DocumentTable.updatedAt],
                )
            }

        val isStudent = viewer.role == UserRole.STUDENT
        return rows.map { row ->
            val lessonId = row[LessonTable.id].value
            val students = studentsByLesson[lessonId] ?: emptyList()
            // Students only read linked documents once they are confirmed on the lesson.
            val showDocuments = !isStudent || students.any { it.id == viewer.id.toString() && it.status == LessonStudentStatus.CONFIRMED.name }
            LessonResponse(
                id = lessonId.toString(),
                title = row[LessonTable.title],
                type = row[LessonTable.type],
                scheduledAt = row[LessonTable.scheduledAt],
                durationMinutes = row[LessonTable.durationMinutes],
                teacherId = row[LessonTable.teacherId].value.toString(),
                teacher = teachersById.getValue(row[LessonTable.teacherId].value),
                status = row[LessonTable.status],
                topic = row[LessonTable.topic],
                level = row[LessonTable.level],
                maxParticipants = row[LessonTable.maxParticipants],
                groupId = row[LessonTable.groupId]?.value?.toString(),
                students = students,
                startedAt = row[LessonTable.startedAt],
                pendingReschedule = pendingByLesson[lessonId],
                cancelReason = row[LessonTable.cancelReason],
                cancelledBy = row[LessonTable.cancelledBy]?.value?.toString(),
                cancelledAt = row[LessonTable.cancelledAt],
                documents = if (showDocuments) documentsByLesson[lessonId].orEmpty() else emptyList(),
                rawNotes = if (isStudent) null else row[LessonTable.rawNotes],
                promptUsed = if (isStudent) null else row[LessonTable.promptUsed],
                topics = topicIdsByLesson[lessonId].orEmpty().mapNotNull(topicRefs::get),
                grammarTopics = grammarIdsByLesson[lessonId].orEmpty().mapNotNull(grammarRefs::get),
                vocab = vocabByLesson[lessonId].orEmpty(),
                correctedSentences = correctionsByLesson[lessonId].orEmpty(),
                vocabDisplayOverride = VocabDisplay.of(row[LessonTable.vocabDisplayFields], row[LessonTable.allowTranslationToggle]),
                createdBy = row[LessonTable.createdBy].value.toString(),
                updatedBy = row[LessonTable.updatedBy]?.value?.toString(),
                createdAt = row[LessonTable.createdAt],
                updatedAt = row[LessonTable.updatedAt],
            )
        }
    }
}

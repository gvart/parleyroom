package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonCorrectionTable
import com.gvart.parleyroom.lesson.data.LessonDocumentTable
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.LessonVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabDisplay
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Teacher-authored lesson content: notes, prompt, tags, words, corrections, vocab display override. */
class LessonContentService(
    private val support: LessonSupport,
    private val documentService: LessonDocumentService,
) {

    fun updateContent(lessonId: UUID, request: UpdateLessonContentRequest, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = requireLessonTeacher(lessonId, principal)
        val teacherId = lesson[LessonTable.teacherId].value

        val groupId = request.groupId?.let(UUID::fromString)?.also { id ->
            if (GroupTable.findByIdOrThrow(id, "Group")[GroupTable.teacherId].value != teacherId)
                throw ForbiddenException("Group belongs to another teacher")
        }
        val topicIds = request.topicIds?.let { LibraryAccess.requireTopics(teacherId, it) }
        val grammarIds = request.grammarTopicIds?.let { LibraryAccess.requireGrammarTopics(teacherId, it) }
        val vocabIds = request.vocabEntryIds?.let { requireEntries(teacherId, it) }

        LessonTable.update({ LessonTable.id eq lessonId }) {
            if (request.rawNotes != null) it[rawNotes] = request.rawNotes
            if (request.promptUsed != null) it[promptUsed] = request.promptUsed
            if (groupId != null) it[LessonTable.groupId] = groupId
            if (request.clearGroup) it[LessonTable.groupId] = null
            it[updatedBy] = principal.id
        }

        topicIds?.let { ids ->
            LessonTopicTable.deleteWhere { LessonTopicTable.lessonId eq lessonId }
            LessonTopicTable.batchInsert(ids) { this[LessonTopicTable.lessonId] = lessonId; this[LessonTopicTable.topicId] = it }
        }
        grammarIds?.let { ids ->
            LessonGrammarTopicTable.deleteWhere { LessonGrammarTopicTable.lessonId eq lessonId }
            LessonGrammarTopicTable.batchInsert(ids) {
                this[LessonGrammarTopicTable.lessonId] = lessonId
                this[LessonGrammarTopicTable.grammarTopicId] = it
            }
        }
        vocabIds?.let { ids ->
            LessonVocabTable.deleteWhere { LessonVocabTable.lessonId eq lessonId }
            LessonVocabTable.batchInsert(ids.withIndex()) { (index, id) ->
                this[LessonVocabTable.lessonId] = lessonId
                this[LessonVocabTable.vocabEntryId] = id
                this[LessonVocabTable.orderIndex] = index
            }
        }
        request.correctedSentences?.let { sentences ->
            val documentId = documentService.ensureDocument(lessonId)
            LessonCorrectionTable.deleteWhere { lessonDocumentId eq documentId }
            LessonCorrectionTable.batchInsert(sentences.withIndex()) { (index, s) ->
                this[LessonCorrectionTable.lessonDocumentId] = documentId
                this[LessonCorrectionTable.incorrect] = s.incorrect.trim()
                this[LessonCorrectionTable.correct] = s.correct.trim()
                this[LessonCorrectionTable.orderIndex] = index
            }
        }

        support.toResponse(support.findLesson(lessonId), principal)
    }

    fun setVocabDisplay(lessonId: UUID, setting: VocabDisplaySetting?, principal: UserPrincipal): LessonResponse = transaction {
        requireLessonTeacher(lessonId, principal)
        setting?.let(VocabDisplay::requireSupportedFields)
        LessonTable.update({ LessonTable.id eq lessonId }) {
            it[vocabDisplayFields] = setting?.fields?.distinct()
            it[allowTranslationToggle] = setting?.allowTranslationToggle
            it[updatedBy] = principal.id
        }
        support.toResponse(support.findLesson(lessonId), principal)
    }

    private fun requireLessonTeacher(lessonId: UUID, principal: UserPrincipal): ResultRow {
        val lesson = support.findLesson(lessonId)
        AuthorizationHelper.requireOwnerOrAdmin(
            lesson[LessonTable.teacherId].value, principal, "Only the lesson's teacher can edit its content",
        )
        return lesson
    }

    private fun requireEntries(teacherId: UUID, ids: List<String>): List<UUID> {
        val uuids = ids.map(UUID::fromString).distinct()
        if (uuids.isEmpty()) return uuids
        val found = VocabEntryTable.selectAll()
            .where { (VocabEntryTable.id inList uuids) and (VocabEntryTable.teacherId eq teacherId) }
            .count()
        if (found != uuids.size.toLong())
            throw NotFoundException("One or more vocab entries not found", code = "VOCAB_ENTRY_NOT_FOUND")
        return uuids
    }
}

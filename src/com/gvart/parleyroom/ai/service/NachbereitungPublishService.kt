package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.transfer.NachbereitungMode
import com.gvart.parleyroom.ai.transfer.PublishRequest
import com.gvart.parleyroom.ai.transfer.PublishResponse
import com.gvart.parleyroom.ai.transfer.PublishTopic
import com.gvart.parleyroom.ai.transfer.PublishedRef
import com.gvart.parleyroom.ai.transfer.PublishedVocab
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.document.service.DocumentService
import com.gvart.parleyroom.document.transfer.DocumentShareRequest
import com.gvart.parleyroom.document.transfer.UpdateDocumentRequest
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.lesson.service.LessonContentService
import com.gvart.parleyroom.lesson.transfer.UpdateLessonContentRequest
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabEntryService
import com.gvart.parleyroom.vocabulary.transfer.AssignVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Publish: one transaction that puts accepted words into the library and to the learners, creates
 * accepted topic / grammar proposals, updates the lesson content and fills + shares the draft.
 * Find-or-create everywhere, so publishing again creates nothing twice.
 */
class NachbereitungPublishService(
    private val context: LessonContextService,
    private val vocabEntryService: VocabEntryService,
    private val lessonContentService: LessonContentService,
    private val documentService: DocumentService,
) {

    fun publish(lessonId: UUID, request: PublishRequest, principal: UserPrincipal): PublishResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        val lesson = context.requireLessonTeacher(lessonId, principal)
        val job = GenerationJobs.requireOwned(UUID.fromString(request.jobId), principal)
        if (job[GenerationJobTable.kind] == GenerationJobKind.FILL_TRANSLATIONS)
            throw ConflictException("Only Nachbereitung jobs can be published", code = "AI_JOB_NOT_READY")
        if (job[GenerationJobTable.lessonId]?.value != lessonId)
            throw BadRequestException("The AI job belongs to another lesson", code = "AI_JOB_LESSON_MISMATCH")
        GenerationJobs.requireSucceeded(job)
        val result = NachbereitungService.nachbereitungResult(job)
        val ctx = context.load(lesson)
        val teacherId = principal.id

        val resultKeys = result.vocab.map { it.key }.toSet()
        request.vocab.map { it.key }.filter { it !in resultKeys }.takeIf { it.isNotEmpty() }?.let {
            throw BadRequestException("Unknown vocab keys: ${it.joinToString()}", code = "VALIDATION_FAILED")
        }
        val topicKeys = request.topics.map { it.key }.toSet()
        (request.vocab.flatMap { it.topicKeys } + request.topics.mapNotNull { it.parentKey })
            .filter { it !in topicKeys }.takeIf { it.isNotEmpty() }?.let {
                throw BadRequestException("Unknown topic keys: ${it.joinToString()} (accept them in topics)", code = "VALIDATION_FAILED")
            }

        // 1. Accepted topic / grammar proposals.
        val matcher = LibraryMatcher(teacherId)
        LibraryAccess.requireTopics(teacherId, request.topics.mapNotNull { it.parentId })
        val topics = createTopics(request.topics, matcher, ctx)
        val grammar = request.grammarTopics.associate { g ->
            g.key to matcher.findOrCreateGrammar(g.name, g.level ?: ctx.level)
        }

        // 2. Words: library find-or-create, assigned to the lesson's confirmed attendees and linked to the lesson.
        val words = request.vocab.map { item ->
            if (item.matchedEntryId != null) {
                val entryId = UUID.fromString(item.matchedEntryId)
                val owned = VocabEntryTable.selectAll()
                    .where { (VocabEntryTable.id eq entryId) and (VocabEntryTable.teacherId eq teacherId) }
                    .empty().not()
                if (!owned) throw NotFoundException("Vocab entry not found", code = "VOCAB_ENTRY_NOT_FOUND")
                val assigned = vocabEntryService.assign(entryId, AssignVocabRequest(lessonId = lessonId.toString()), principal)
                Word(item.key, entryId, reused = true, assigned = assigned.assigned)
            } else {
                val entry = item.entry!!.let { e ->
                    e.copy(
                        topicIds = (e.topicIds + item.topicKeys.map { topics.getValue(it).first.toString() }).distinct(),
                        sourceLessonId = e.sourceLessonId ?: lessonId.toString(),
                    )
                }
                val added = vocabEntryService.quickAdd(QuickAddVocabRequest(entry = entry, lessonId = lessonId.toString()), principal)
                Word(item.key, UUID.fromString(added.entry.id), reused = added.reused, assigned = added.assigned)
            }
        }
        val entryByKey = result.publishedEntries + words.associate { it.key to it.entryId.toString() }

        // 3. Lesson content: notes, prompt, tags (union), corrected sentences.
        val input = GenerationJobs.input(job)
        val lessonTopics = currentIds(LessonTopicTable.select(LessonTopicTable.topicId).where { LessonTopicTable.lessonId eq lessonId }
            .map { it[LessonTopicTable.topicId].value })
        val lessonGrammar = currentIds(LessonGrammarTopicTable.select(LessonGrammarTopicTable.grammarTopicId)
            .where { LessonGrammarTopicTable.lessonId eq lessonId }.map { it[LessonGrammarTopicTable.grammarTopicId].value })
        val topicIds = (lessonTopics + request.topicIds + topics.values.map { it.first.toString() }).distinct()
        val grammarTopicIds = (lessonGrammar + request.grammarTopicIds + grammar.values.map { it.first.toString() }).distinct()
        lessonContentService.updateContent(
            lessonId,
            UpdateLessonContentRequest(
                rawNotes = input.notes,
                promptUsed = input.prompt,
                topicIds = topicIds,
                grammarTopicIds = grammarTopicIds,
                correctedSentences = request.correctedSentences,
            ),
            principal,
        )

        // 4. Document: fill the vocab tables, tag, link to the lesson and share.
        val documentId = UUID.fromString(result.documentId)
        val document = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
            ?: throw NotFoundException("The draft document was deleted", code = "DOCUMENT_NOT_FOUND")
        val published = words.map { it.key }.toSet()
        val tables = result.vocabTables.associate { it.blockId to it.vocabKeys.filter { key -> key in published }.mapNotNull(entryByKey::get) }
        val documentTopics = DocumentTopicTable.select(DocumentTopicTable.topicId).where { DocumentTopicTable.documentId eq documentId }
            .map { it[DocumentTopicTable.topicId].value.toString() }
        val documentGrammar = DocumentGrammarTopicTable.select(DocumentGrammarTopicTable.grammarTopicId)
            .where { DocumentGrammarTopicTable.documentId eq documentId }.map { it[DocumentGrammarTopicTable.grammarTopicId].value.toString() }
        documentService.updateDocument(
            documentId,
            UpdateDocumentRequest(
                title = document[DocumentTable.title],
                level = document[DocumentTable.level],
                topicIds = (documentTopics + topicIds).distinct(),
                grammarTopicIds = (documentGrammar + grammarTopicIds).distinct(),
                audience = document[DocumentTable.audience],
                blocks = AiBlocks.fillRows(document[DocumentTable.blocks], tables),
                revision = document[DocumentTable.revision],
            ),
            principal,
        )
        if (request.share) {
            documentService.linkLesson(lessonId, documentId, principal)
            val share = when {
                ctx.mode == NachbereitungMode.CLUB && ctx.groupId != null -> DocumentShareRequest(groupIds = listOf(ctx.groupId.toString()))
                // Club without a group: the lesson link already makes it readable by the confirmed attendees.
                ctx.mode == NachbereitungMode.CLUB -> null
                else -> linkedStudents(teacherId, ctx.attendeeIds).takeIf { it.isNotEmpty() }
                    ?.let { ids -> DocumentShareRequest(studentIds = ids.map(UUID::toString)) }
            }
            share?.let { documentService.share(documentId, it, principal) }
        }

        // 5. Remember what was published on the job.
        val now = OffsetDateTime.now()
        val updatedResult = result.copy(publishedEntries = entryByKey)
        GenerationJobTable.update({ GenerationJobTable.id eq job[GenerationJobTable.id] }) {
            it[publishedAt] = now
            it[GenerationJobTable.result] = GenerationJobs.json.encodeToJsonElement(updatedResult)
        }

        PublishResponse(
            documentId = documentId.toString(),
            revision = DocumentTable.select(DocumentTable.revision).where { DocumentTable.id eq documentId }.single()[DocumentTable.revision],
            wordsCreated = words.count { !it.reused },
            wordsReused = words.count { it.reused },
            wordsAssigned = words.sumOf { it.assigned },
            recipients = ctx.attendees.size,
            recipientIds = ctx.attendees.map { it.id },
            topicsCreated = topics.values.count { !it.second },
            grammarTopicsCreated = grammar.values.count { !it.second },
            vocab = words.map { PublishedVocab(it.key, it.entryId.toString(), it.reused) },
            topics = topics.map { (key, value) -> PublishedRef(key, value.first.toString(), value.second) },
            grammarTopics = grammar.map { (key, value) -> PublishedRef(key, value.first.toString(), value.second) },
            publishedAt = now,
        )
    }

    private data class Word(val key: String, val entryId: UUID, val reused: Boolean, val assigned: Int)

    private fun currentIds(ids: List<UUID>) = ids.map(UUID::toString)

    /** Creates topics parents-first (parentKey may point at another accepted proposal). */
    private fun createTopics(requested: List<PublishTopic>, matcher: LibraryMatcher, ctx: LessonContext): Map<String, Pair<UUID, Boolean>> {
        val done = linkedMapOf<String, Pair<UUID, Boolean>>()
        var pending = requested
        while (pending.isNotEmpty()) {
            val (ready, waiting) = pending.partition { it.parentKey == null || it.parentKey in done }
            if (ready.isEmpty()) throw BadRequestException("Topic parentKey references form a cycle", code = "VALIDATION_FAILED")
            ready.forEach { topic ->
                val parentId = topic.parentId?.let(UUID::fromString) ?: topic.parentKey?.let { done.getValue(it).first }
                done[topic.key] = matcher.findOrCreateTopic(topic.name, parentId, ctx.level)
            }
            pending = waiting
        }
        return done
    }

    private fun linkedStudents(teacherId: UUID, studentIds: List<UUID>): List<UUID> {
        if (studentIds.isEmpty()) return emptyList()
        return TeacherStudentTable.select(TeacherStudentTable.studentId)
            .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId inList studentIds) }
            .map { it[TeacherStudentTable.studentId].value }
    }
}

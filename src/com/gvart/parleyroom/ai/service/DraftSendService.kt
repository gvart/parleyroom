package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.DraftBundleTable
import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.DraftItemTable
import com.gvart.parleyroom.ai.data.DraftMode
import com.gvart.parleyroom.ai.data.DraftStatus
import com.gvart.parleyroom.ai.transfer.DraftDocument
import com.gvart.parleyroom.ai.transfer.DraftGrammarTopic
import com.gvart.parleyroom.ai.transfer.DraftTopic
import com.gvart.parleyroom.ai.transfer.SendDraftRequest
import com.gvart.parleyroom.ai.transfer.SendDraftResponse
import com.gvart.parleyroom.ai.transfer.SentWord
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.data.DocumentStudentTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.service.DocumentService
import com.gvart.parleyroom.document.transfer.CreateDocumentRequest
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.service.AssignmentService
import com.gvart.parleyroom.homework.transfer.AssignmentItemInput
import com.gvart.parleyroom.homework.transfer.CreateAssignmentRequest
import com.gvart.parleyroom.lesson.data.LessonGrammarTopicTable
import com.gvart.parleyroom.lesson.data.LessonTopicTable
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.service.VocabEntryService
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Send: the only way AI output reaches students. One transaction applies the approved items of a
 * draft bundle — words to the library and the recipients, the exercise document + tasks as one
 * assignment (HOMEWORK_ASSIGNED), the documents shared — and marks the bundle SENT. A repeated
 * Send returns the stored result. `lessons.raw_notes` is never written.
 */
class DraftSendService(
    private val drafts: DraftBundleService,
    private val vocabEntryService: VocabEntryService,
    private val documentService: DocumentService,
    private val assignmentService: AssignmentService,
) {

    fun send(bundleId: UUID, request: SendDraftRequest, principal: UserPrincipal): SendDraftResponse = transaction {
        drafts.requireOwned(bundleId, principal)
        // Serializes concurrent sends: the second one waits and then returns the stored result.
        val bundle = DraftBundleTable.selectAll().where { DraftBundleTable.id eq bundleId }.forUpdate().single()
        bundle[DraftBundleTable.sendResult]?.let { return@transaction GenerationJobs.json.decodeFromJsonElement<SendDraftResponse>(it) }
        if (bundle[DraftBundleTable.status] != DraftStatus.DRAFT)
            throw ConflictException("The draft was discarded", code = "AI_DRAFT_NOT_EDITABLE")
        drafts.requireIdle(bundleId)

        val items = drafts.itemRows(bundleId)
        val approved = items.filter { it[DraftItemTable.approved] }
        if (approved.isEmpty()) throw BadRequestException("Approve at least one item before sending", code = "AI_DRAFT_NOTHING_APPROVED")

        val candidates = drafts.recipients(bundle).map { UUID.fromString(it.id) }
        val recipients = request.studentIds?.map { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it in candidates }
                ?: throw BadRequestException("Not a recipient of this draft: $raw", code = "AI_DRAFT_RECIPIENT_INVALID")
        }?.distinct() ?: candidates
        if (recipients.isEmpty()) throw BadRequestException("The draft has no recipients", code = "AI_DRAFT_NO_RECIPIENTS")

        val lessonId = bundle[DraftBundleTable.lessonId]?.value
        val everyone = recipients.toSet() == candidates.toSet()
        fun ofKind(kind: DraftItemKind) = approved.filter { it[DraftItemTable.kind] == kind }
        val words = ofKind(DraftItemKind.WORD).map { it to DraftItems.word(it) }
        val exercise = ofKind(DraftItemKind.EXERCISE_DOCUMENT).firstOrNull()?.let(DraftItems::document)
        val tasks = ofKind(DraftItemKind.TASK).map(DraftItems::task)
        val notes = ofKind(DraftItemKind.NOTES_DOCUMENT).firstOrNull()?.let(DraftItems::document)
        val tags = Tags(principal.id, (exercise ?: notes)?.level)

        // 1. Words: find-or-create in the library (a matching entry is reused as is), assign to the recipients.
        // Suggested new topics / grammar topics are created here, not at generation.
        val sentWords = words.map { (row, word) ->
            val entry = word.entry.copy(
                topicIds = word.topics.map { tags.topic(it).toString() }.distinct(),
                grammarTopicIds = word.grammarTopics.map { tags.grammar(it).toString() }.distinct(),
                sourceLessonId = word.entry.sourceLessonId ?: lessonId?.toString(),
            )
            val added = vocabEntryService.quickAdd(
                QuickAddVocabRequest(entry = entry, studentIds = recipients.map(UUID::toString), lessonId = lessonId?.toString()),
                principal,
            )
            SentWord(row[DraftItemTable.id].value.toString(), added.entry.id, added.reused) to added.assigned
        }

        // 2. Documents: created, shared with the recipients, linked to the lesson when nobody was deselected.
        fun create(doc: DraftDocument, audience: DocumentAudience, topics: List<DraftTopic>, grammar: List<DraftGrammarTopic>): UUID {
            val created = documentService.createDocument(
                CreateDocumentRequest(
                    title = doc.title,
                    level = doc.level,
                    topicIds = (doc.topics + topics).map { tags.topic(it).toString() }.distinct(),
                    grammarTopicIds = (doc.grammarTopics + grammar).map { tags.grammar(it).toString() }.distinct(),
                    audience = audience,
                    blocks = doc.blocks,
                ),
                principal,
            )
            val documentId = UUID.fromString(created.id)
            if (lessonId != null) {
                DocumentTable.update({ DocumentTable.id eq documentId }) { it[createdFromLessonId] = lessonId }
                if (everyone) documentService.linkLesson(lessonId, documentId, principal)
            }
            val now = OffsetDateTime.now()
            recipients.forEach { studentId ->
                DocumentStudentTable.insertIgnore {
                    it[DocumentStudentTable.documentId] = documentId
                    it[DocumentStudentTable.studentId] = studentId
                    it[sharedAt] = now
                }
            }
            return documentId
        }
        val club = bundle[DraftBundleTable.mode] == DraftMode.CLUB
        val exerciseId = exercise?.let {
            create(it, DocumentAudience.STUDENT, tasks.flatMap { t -> t.topics }, tasks.flatMap { t -> t.grammarTopics } + words.flatMap { w -> w.second.grammarTopics })
        }
        val notesId = notes?.let { create(it, if (club) DocumentAudience.GROUP else DocumentAudience.STUDENT, emptyList(), emptyList()) }

        // 3. Homework: the exercise document and the tasks as one assignment (notifies HOMEWORK_ASSIGNED).
        val assignment = if (exerciseId != null || tasks.isNotEmpty()) assignmentService.create(
            CreateAssignmentRequest(
                title = request.title?.trim() ?: exercise?.title ?: tasks.first().title,
                instructions = request.instructions,
                dueDate = request.dueDate ?: LocalDate.now().plusDays(DEFAULT_DUE_DAYS).toString(),
                lessonId = lessonId?.toString(),
                studentIds = recipients.map(UUID::toString),
                items = listOfNotNull(exerciseId?.let { AssignmentItemInput(AssignmentItemKind.DOCUMENT, documentId = it.toString()) }) +
                        tasks.map { AssignmentItemInput(AssignmentItemKind.TASK, title = it.title, task = it.instructions, responseType = it.responseType) },
            ),
            principal,
        ) else null

        // 4. The lesson gets the approved tags (never its notes).
        if (lessonId != null) {
            val topicIds = (words.flatMap { it.second.topics } + exercise?.topics.orEmpty() + notes?.topics.orEmpty() + tasks.flatMap { it.topics })
                .map(tags::topic).distinct()
            val grammarIds = (words.flatMap { it.second.grammarTopics } + exercise?.grammarTopics.orEmpty() + notes?.grammarTopics.orEmpty() +
                    tasks.flatMap { it.grammarTopics }).map(tags::grammar).distinct()
            topicIds.forEach { id -> LessonTopicTable.insertIgnore { it[LessonTopicTable.lessonId] = lessonId; it[topicId] = id } }
            grammarIds.forEach { id -> LessonGrammarTopicTable.insertIgnore { it[LessonGrammarTopicTable.lessonId] = lessonId; it[grammarTopicId] = id } }
        }

        val now = OffsetDateTime.now()
        val approvedIds = approved.map { it[DraftItemTable.id].value.toString() }
        val result = SendDraftResponse(
            bundleId = bundleId.toString(),
            sentAt = now,
            recipientIds = recipients.map(UUID::toString),
            words = sentWords.map { it.first },
            wordsAssigned = sentWords.sumOf { it.second },
            exerciseDocumentId = exerciseId?.toString(),
            notesDocumentId = notesId?.toString(),
            assignmentId = assignment?.id,
            sentItemIds = approvedIds,
            skippedItemIds = items.map { it[DraftItemTable.id].value.toString() }.filter { it !in approvedIds },
            topicsCreated = tags.topicsCreated,
            grammarTopicsCreated = tags.grammarCreated,
        )
        DraftBundleTable.update({ DraftBundleTable.id eq bundleId }) {
            it[status] = DraftStatus.SENT
            it[sentAt] = now
            it[sendResult] = GenerationJobs.json.encodeToJsonElement(result)
        }
        result
    }

    /** Tag resolution: an id still in the library is used, otherwise the name is found or created. Must run in a transaction. */
    private class Tags(private val teacherId: UUID, private val level: LanguageLevel?) {
        private val matcher = LibraryMatcher(teacherId)
        private val topicIds = TopicTable.select(TopicTable.id).where { TopicTable.teacherId eq teacherId }.map { it[TopicTable.id].value }.toSet()
        private val grammarIds = GrammarTopicTable.select(GrammarTopicTable.id).where { GrammarTopicTable.teacherId eq teacherId }
            .map { it[GrammarTopicTable.id].value }.toSet()
        var topicsCreated = 0
        var grammarCreated = 0

        fun topic(tag: DraftTopic): UUID {
            tag.id?.let(::uuid)?.takeIf { it in topicIds }?.let { return it }
            val parentId = tag.parentName?.let { matcher.matchTopic(it, null) }
            val (id, reused) = matcher.findOrCreateTopic(tag.name, parentId, level)
            if (!reused) topicsCreated++
            return id
        }

        fun grammar(tag: DraftGrammarTopic): UUID {
            tag.id?.let(::uuid)?.takeIf { it in grammarIds }?.let { return it }
            val (id, reused) = matcher.findOrCreateGrammar(tag.name, tag.level ?: level)
            if (!reused) grammarCreated++
            return id
        }

        private fun uuid(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()
    }

    companion object {
        const val DEFAULT_DUE_DAYS = 7L
    }
}

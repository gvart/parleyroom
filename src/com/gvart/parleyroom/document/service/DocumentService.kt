package com.gvart.parleyroom.document.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.data.DocumentGrammarTopicTable
import com.gvart.parleyroom.document.data.DocumentGroupTable
import com.gvart.parleyroom.document.data.DocumentLessonTable
import com.gvart.parleyroom.document.data.DocumentStudentTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.data.DocumentTopicTable
import com.gvart.parleyroom.document.data.DocumentVersionReason
import com.gvart.parleyroom.document.transfer.CreateDocumentRequest
import com.gvart.parleyroom.document.transfer.DocumentInput
import com.gvart.parleyroom.document.transfer.DocumentSummary
import com.gvart.parleyroom.document.transfer.UpdateDocumentRequest
import com.gvart.parleyroom.document.transfer.DocumentPageResponse
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.document.transfer.DocumentShareRequest
import com.gvart.parleyroom.document.transfer.DuplicateDocumentRequest
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

class DocumentService(
    private val support: DocumentSupport,
    private val versions: DocumentVersionService,
) {

    data class Filters(
        val level: LanguageLevel? = null,
        val topicId: UUID? = null,
        val grammarTopicId: UUID? = null,
        val audience: DocumentAudience? = null,
        val lessonId: UUID? = null,
        val studentId: UUID? = null,
        val groupId: UUID? = null,
        val q: String? = null,
    )

    fun listDocuments(principal: UserPrincipal, filters: Filters, page: PageRequest): DocumentPageResponse = transaction {
        val query = DocumentTable.selectAll()
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> query.andWhere { DocumentTable.ownerId eq principal.id }
            UserRole.STUDENT -> {
                val readable = support.readableByStudent(principal.id)
                if (readable.isEmpty()) return@transaction DocumentPageResponse(emptyList(), 0, page.page, page.pageSize)
                query.andWhere { DocumentTable.id inList readable }
            }
        }
        filters.level?.let { level -> query.andWhere { DocumentTable.level eq level } }
        filters.audience?.let { audience -> query.andWhere { DocumentTable.audience eq audience } }
        filters.topicId?.let { query.andWhere { linkedTo(DocumentTopicTable, DocumentTopicTable.documentId, DocumentTopicTable.topicId, it) } }
        filters.grammarTopicId?.let {
            query.andWhere { linkedTo(DocumentGrammarTopicTable, DocumentGrammarTopicTable.documentId, DocumentGrammarTopicTable.grammarTopicId, it) }
        }
        filters.lessonId?.let { query.andWhere { linkedTo(DocumentLessonTable, DocumentLessonTable.documentId, DocumentLessonTable.lessonId, it) } }
        filters.studentId?.let { query.andWhere { linkedTo(DocumentStudentTable, DocumentStudentTable.documentId, DocumentStudentTable.studentId, it) } }
        filters.groupId?.let { query.andWhere { linkedTo(DocumentGroupTable, DocumentGroupTable.documentId, DocumentGroupTable.groupId, it) } }
        filters.q?.takeIf { it.isNotBlank() }?.let { q ->
            query.andWhere { DocumentTable.title.lowerCase() like "%${escapeLike(q.trim().lowercase())}%" }
        }

        val total = query.count()
        val rows = query.orderBy(DocumentTable.updatedAt, SortOrder.DESC)
            .limit(page.pageSize).offset(page.offset)
            .toList()
        DocumentPageResponse(support.toSummaries(rows, principal), total, page.page, page.pageSize)
    }

    fun getDocument(documentId: UUID, principal: UserPrincipal): DocumentResponse = transaction {
        support.toResponse(support.requireReadable(documentId, principal), principal)
    }

    fun createDocument(request: CreateDocumentRequest, principal: UserPrincipal): DocumentResponse = transaction {
        LibraryAccess.requireTeacher(principal)
        val ownerId = principal.id
        val input = request.toInput()
        val tags = validateInput(ownerId, input)
        val studentIds = requireLinkedStudents(ownerId, request.studentIds)
        val groupIds = requireOwnGroups(ownerId, request.groupIds)
        val createdFromLessonId = request.createdFromLessonId?.let(UUID::fromString)
        val lessonIds = requireOwnLessons(ownerId, request.lessonIds + listOfNotNull(request.createdFromLessonId))

        val now = OffsetDateTime.now()
        val id = DocumentTable.insertAndGetId {
            it[DocumentTable.ownerId] = ownerId
            it[title] = input.title.trim()
            it[level] = input.level
            it[audience] = input.audience
            it[blocks] = input.blocks
            it[DocumentTable.createdFromLessonId] = createdFromLessonId
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        replaceTags(id, tags)
        insertLinks(DocumentStudentTable, DocumentStudentTable.documentId, DocumentStudentTable.studentId, DocumentStudentTable.sharedAt, id, studentIds)
        insertLinks(DocumentGroupTable, DocumentGroupTable.documentId, DocumentGroupTable.groupId, DocumentGroupTable.sharedAt, id, groupIds)
        insertLinks(DocumentLessonTable, DocumentLessonTable.documentId, DocumentLessonTable.lessonId, DocumentLessonTable.linkedAt, id, lessonIds)
        support.toResponse(support.findDocument(id), principal)
    }

    /** Full replace, guarded by the revision the client read (optimistic concurrency). */
    fun updateDocument(documentId: UUID, request: UpdateDocumentRequest, principal: UserPrincipal): DocumentResponse = transaction {
        val document = support.requireOwned(documentId, principal)
        if (document[DocumentTable.revision] != request.revision) throw conflict(document[DocumentTable.revision])
        val input = request.toInput()
        val tags = validateInput(principal.id, input)
        versions.snapshotForAutosave(document, principal)
        val updated = DocumentTable.update({ (DocumentTable.id eq documentId) and (DocumentTable.revision eq request.revision) }) {
            it[title] = input.title.trim()
            it[level] = input.level
            it[audience] = input.audience
            it[blocks] = input.blocks
            it[revision] = DocumentTable.revision + 1
            it[updatedAt] = OffsetDateTime.now()
        }
        // A concurrent write got in between the read and this update.
        if (updated == 0) throw conflict(support.findDocument(documentId)[DocumentTable.revision])
        replaceTags(documentId, tags)
        support.toResponse(support.findDocument(documentId), principal)
    }

    fun deleteDocument(documentId: UUID, principal: UserPrincipal) = transaction {
        support.requireOwnedOrAdmin(documentId, principal)
        DocumentTable.deleteWhere { id eq documentId }
    }

    /** Copies content and tags with fresh block/item ids. The copy is not shared and not linked to lessons. */
    fun duplicateDocument(documentId: UUID, request: DuplicateDocumentRequest, principal: UserPrincipal): DocumentResponse = transaction {
        val source = support.requireOwned(documentId, principal)
        versions.snapshot(source, DocumentVersionReason.DUPLICATE, principal)
        val now = OffsetDateTime.now()
        val id = DocumentTable.insertAndGetId {
            it[ownerId] = principal.id
            it[title] = request.title?.trim() ?: source[DocumentTable.title]
            it[level] = source[DocumentTable.level]
            it[audience] = source[DocumentTable.audience]
            it[blocks] = withNewIds(source[DocumentTable.blocks])
            it[createdFromLessonId] = source[DocumentTable.createdFromLessonId]
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        val topicIds = DocumentTopicTable.select(DocumentTopicTable.topicId)
            .where { DocumentTopicTable.documentId eq documentId }
            .map { it[DocumentTopicTable.topicId].value }
        val grammarTopicIds = DocumentGrammarTopicTable.select(DocumentGrammarTopicTable.grammarTopicId)
            .where { DocumentGrammarTopicTable.documentId eq documentId }
            .map { it[DocumentGrammarTopicTable.grammarTopicId].value }
        replaceTags(id, Tags(topicIds, grammarTopicIds))
        support.toResponse(support.findDocument(id), principal)
    }

    fun share(documentId: UUID, request: DocumentShareRequest, principal: UserPrincipal): DocumentResponse = transaction {
        val document = support.requireOwned(documentId, principal)
        val studentIds = requireLinkedStudents(principal.id, request.studentIds)
        val groupIds = requireOwnGroups(principal.id, request.groupIds)
        versions.snapshot(document, DocumentVersionReason.SHARE, principal)

        val sharedStudents = DocumentStudentTable.select(DocumentStudentTable.studentId)
            .where { DocumentStudentTable.documentId eq documentId }
            .map { it[DocumentStudentTable.studentId].value }.toSet()
        val sharedGroups = DocumentGroupTable.select(DocumentGroupTable.groupId)
            .where { DocumentGroupTable.documentId eq documentId }
            .map { it[DocumentGroupTable.groupId].value }.toSet()
        insertLinks(DocumentStudentTable, DocumentStudentTable.documentId, DocumentStudentTable.studentId, DocumentStudentTable.sharedAt,
            documentId, studentIds - sharedStudents)
        insertLinks(DocumentGroupTable, DocumentGroupTable.documentId, DocumentGroupTable.groupId, DocumentGroupTable.sharedAt,
            documentId, groupIds - sharedGroups)
        support.bumpRevision(documentId)
        support.toResponse(support.findDocument(documentId), principal)
    }

    fun unshare(documentId: UUID, request: DocumentShareRequest, principal: UserPrincipal): DocumentResponse = transaction {
        val document = support.requireOwned(documentId, principal)
        val studentIds = request.studentIds.map(UUID::fromString)
        val groupIds = request.groupIds.map(UUID::fromString)
        if (studentIds.isNotEmpty()) DocumentStudentTable.deleteWhere {
            (DocumentStudentTable.documentId eq documentId) and (DocumentStudentTable.studentId inList studentIds)
        }
        if (groupIds.isNotEmpty()) DocumentGroupTable.deleteWhere {
            (DocumentGroupTable.documentId eq documentId) and (DocumentGroupTable.groupId inList groupIds)
        }
        support.bumpRevision(documentId)
        support.toResponse(support.findDocument(documentId), principal)
    }

    /** Linked documents the caller can read: the lesson's teacher and admins see all, confirmed students their readable ones. */
    fun lessonDocuments(lessonId: UUID, principal: UserPrincipal): List<DocumentSummary> = transaction {
        val lesson = LessonTable.findByIdOrThrow(lessonId, "Lesson")
        val linked = DocumentLessonTable.select(DocumentLessonTable.documentId)
            .where { DocumentLessonTable.lessonId eq lessonId }
            .map { it[DocumentLessonTable.documentId].value }
        val visible = when (principal.role) {
            UserRole.ADMIN -> linked
            UserRole.TEACHER -> {
                if (lesson[LessonTable.teacherId].value != principal.id) throw ForbiddenException("Not your lesson")
                linked
            }
            UserRole.STUDENT -> {
                val confirmed = LessonStudentTable.selectAll()
                    .where {
                        (LessonStudentTable.lessonId eq lessonId) and (LessonStudentTable.studentId eq principal.id) and
                                (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                    }
                    .empty().not()
                if (!confirmed) throw ForbiddenException("Only participants of this lesson can see its documents")
                val readable = support.readableByStudent(principal.id)
                linked.filter { it in readable }
            }
        }
        if (visible.isEmpty()) return@transaction emptyList()
        val rows = DocumentTable.selectAll()
            .where { DocumentTable.id inList visible }
            .orderBy(DocumentTable.updatedAt, SortOrder.DESC)
            .toList()
        support.toSummaries(rows, principal)
    }

    /** Links a document to a lesson: both must belong to the same teacher. */
    fun linkLesson(lessonId: UUID, documentId: UUID, principal: UserPrincipal) = transaction {
        support.requireOwned(documentId, principal)
        requireOwnLessons(principal.id, listOf(lessonId.toString()))
        val linked = DocumentLessonTable.selectAll()
            .where { (DocumentLessonTable.documentId eq documentId) and (DocumentLessonTable.lessonId eq lessonId) }
            .empty().not()
        if (!linked) insertLinks(DocumentLessonTable, DocumentLessonTable.documentId, DocumentLessonTable.lessonId, DocumentLessonTable.linkedAt,
            documentId, listOf(lessonId))
    }

    fun unlinkLesson(lessonId: UUID, documentId: UUID, principal: UserPrincipal) = transaction {
        support.requireOwned(documentId, principal)
        DocumentLessonTable.deleteWhere { (DocumentLessonTable.documentId eq documentId) and (DocumentLessonTable.lessonId eq lessonId) }
    }

    private data class Tags(val topicIds: List<UUID>, val grammarTopicIds: List<UUID>)

    /** Schema + semantic validation, and every library reference must belong to the owner. */
    private fun validateInput(ownerId: UUID, input: DocumentInput): Tags {
        val references = DocumentBlockValidator.validate(input.blocks)
        requireOwned(references.vocabEntries, "vocab entry") { ids ->
            VocabEntryTable.select(VocabEntryTable.id)
                .where { (VocabEntryTable.id inList ids) and (VocabEntryTable.teacherId eq ownerId) }
                .map { it[VocabEntryTable.id].value }
        }
        requireOwned(references.materials, "material") { ids ->
            MaterialTable.select(MaterialTable.id)
                .where { (MaterialTable.id inList ids) and (MaterialTable.teacherId eq ownerId) }
                .map { it[MaterialTable.id].value }
        }
        return Tags(
            topicIds = LibraryAccess.requireTopics(ownerId, input.topicIds),
            grammarTopicIds = LibraryAccess.requireGrammarTopics(ownerId, input.grammarTopicIds),
        )
    }

    private fun requireOwned(references: Map<String, UUID>, what: String, findOwned: (List<UUID>) -> List<UUID>) {
        if (references.isEmpty()) return
        val owned = findOwned(references.values.distinct()).toSet()
        references.entries.firstOrNull { it.value !in owned }?.let { (pointer, id) ->
            throw DocumentBlockValidator.invalid(pointer, "$what $id is not in your library")
        }
    }

    private fun replaceTags(documentId: UUID, tags: Tags) {
        DocumentTopicTable.deleteWhere { DocumentTopicTable.documentId eq documentId }
        DocumentGrammarTopicTable.deleteWhere { DocumentGrammarTopicTable.documentId eq documentId }
        DocumentTopicTable.batchInsert(tags.topicIds) {
            this[DocumentTopicTable.documentId] = documentId
            this[DocumentTopicTable.topicId] = it
        }
        DocumentGrammarTopicTable.batchInsert(tags.grammarTopicIds) {
            this[DocumentGrammarTopicTable.documentId] = documentId
            this[DocumentGrammarTopicTable.grammarTopicId] = it
        }
    }

    private fun insertLinks(
        table: Table,
        documentColumn: Column<EntityID<UUID>>,
        targetColumn: Column<EntityID<UUID>>,
        timestampColumn: Column<OffsetDateTime>,
        documentId: UUID,
        targetIds: Collection<UUID>,
    ) {
        if (targetIds.isEmpty()) return
        val now = OffsetDateTime.now()
        table.batchInsert(targetIds) {
            this[documentColumn] = documentId
            this[targetColumn] = it
            this[timestampColumn] = now
        }
    }

    private fun linkedTo(table: Table, documentColumn: Column<EntityID<UUID>>, targetColumn: Column<EntityID<UUID>>, targetId: UUID) =
        DocumentTable.id inSubQuery table.select(documentColumn).where { targetColumn eq targetId }

    private fun requireLinkedStudents(teacherId: UUID, ids: List<String>): List<UUID> {
        val uuids = ids.map(UUID::fromString).distinct()
        if (uuids.isEmpty()) return uuids
        val linked = TeacherStudentTable.select(TeacherStudentTable.studentId)
            .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId inList uuids) }
            .map { it[TeacherStudentTable.studentId].value }
            .toSet()
        val unlinked = uuids.filter { it !in linked }
        if (unlinked.isNotEmpty())
            throw BadRequestException(
                "Teacher does not have a relationship with students: ${unlinked.joinToString()}",
                code = "STUDENT_NOT_LINKED",
            )
        return uuids
    }

    private fun requireOwnGroups(teacherId: UUID, ids: List<String>): List<UUID> {
        val uuids = ids.map(UUID::fromString).distinct()
        if (uuids.isEmpty()) return uuids
        val found = GroupTable.selectAll()
            .where { (GroupTable.id inList uuids) and (GroupTable.teacherId eq teacherId) }
            .count()
        if (found != uuids.size.toLong()) throw NotFoundException("One or more groups not found", code = "GROUP_NOT_FOUND")
        return uuids
    }

    private fun requireOwnLessons(teacherId: UUID, ids: List<String>): List<UUID> {
        val uuids = ids.map(UUID::fromString).distinct()
        if (uuids.isEmpty()) return uuids
        val found = LessonTable.selectAll()
            .where { (LessonTable.id inList uuids) and (LessonTable.teacherId eq teacherId) }
            .count()
        if (found != uuids.size.toLong()) throw NotFoundException("One or more lessons not found", code = "LESSON_NOT_FOUND")
        return uuids
    }

    /**
     * Gives every block, item, option and row a new id, and remaps option references in
     * solutions. Library references (vocabEntryId, materialId, topicId) are kept.
     */
    private fun withNewIds(blocks: JsonArray): JsonArray {
        val mapping = mutableMapOf<String, String>()
        fun remap(id: String) = mapping.getOrPut(id.lowercase()) { UUID.randomUUID().toString() }
        // Only blocks, items, options and rows carry an "id" key (rich text nodes never do).
        fun copy(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) ->
                when {
                    key == "id" && value is JsonPrimitive -> JsonPrimitive(remap(value.content))
                    key == "correctOptionIds" && value is JsonArray ->
                        JsonArray(value.map { JsonPrimitive(remap((it as JsonPrimitive).content)) })
                    else -> copy(value)
                }
            })
            is JsonArray -> JsonArray(element.map(::copy))
            else -> element
        }
        return copy(blocks) as JsonArray
    }

    private fun conflict(currentRevision: Int) = ConflictException(
        "Document was changed in the meantime (current revision $currentRevision)",
        code = "DOCUMENT_CONFLICT",
        currentRevision = currentRevision,
    )

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

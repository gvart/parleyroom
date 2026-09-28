package com.gvart.parleyroom.homework.service

import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.group.data.GroupMemberTable
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.homework.data.AssignmentGroupTable
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.data.HomeworkUploadTable
import com.gvart.parleyroom.homework.transfer.AssignmentItemInput
import com.gvart.parleyroom.homework.transfer.AssignmentPageResponse
import com.gvart.parleyroom.homework.transfer.AssignmentResponse
import com.gvart.parleyroom.homework.transfer.AssignmentSummary
import com.gvart.parleyroom.homework.transfer.CreateAssignmentRequest
import com.gvart.parleyroom.homework.transfer.UpdateAssignmentRequest
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Teacher side: an assignment is created once and expands to one homework per student. */
class AssignmentService(
    private val views: HomeworkViews,
    private val notificationService: NotificationService,
    private val storage: StorageService,
) {

    fun create(request: CreateAssignmentRequest, principal: UserPrincipal): AssignmentResponse = transaction {
        if (principal.role != UserRole.TEACHER) throw ForbiddenException("Only teachers can assign homework")
        val teacherId = principal.id

        val studentIds = requireLinkedStudents(teacherId, request.studentIds.map { parseId(it, "studentIds") })
        val groupIds = request.groupIds.map { parseId(it, "groupIds") }.distinct()
        if (groupIds.isNotEmpty()) {
            val owned = GroupTable.select(GroupTable.id)
                .where { (GroupTable.id inList groupIds) and (GroupTable.teacherId eq teacherId) }
                .map { it[GroupTable.id].value }.toSet()
            if (owned.size != groupIds.size) throw NotFoundException("Group not found", code = "GROUP_NOT_FOUND")
        }
        val members = if (groupIds.isEmpty()) emptyList() else GroupMemberTable.select(GroupMemberTable.studentId)
            .where { GroupMemberTable.groupId inList groupIds }
            .map { it[GroupMemberTable.studentId].value }
        val recipients = (studentIds + members).distinct()
        if (recipients.isEmpty())
            throw BadRequestException("The assignment has no students", code = "ASSIGNMENT_NO_STUDENTS")

        val lessonId = request.lessonId?.let { parseId(it, "lessonId") }?.also { id ->
            LessonTable.select(LessonTable.id)
                .where { (LessonTable.id eq id) and (LessonTable.teacherId eq teacherId) }
                .singleOrNull() ?: throw NotFoundException("Lesson not found", code = "LESSON_NOT_FOUND")
        }
        val dueDate = request.dueDate?.let(::parseDate)
        val items = request.items.mapIndexed { index, input -> resolveItem(input, index, teacherId) }
        val totalUnits = items.sumOf { item ->
            HomeworkUnits.units(ItemSource(UUID.randomUUID(), item.kind, item.responseType, item.blocks)).size
        }

        val assignmentId = AssignmentTable.insertAndGetId {
            it[AssignmentTable.teacherId] = teacherId
            it[AssignmentTable.lessonId] = lessonId
            it[title] = request.title.trim()
            it[instructions] = request.instructions?.takeIf(String::isNotBlank)
            it[AssignmentTable.dueDate] = dueDate
            it[itemCount] = items.size
            it[AssignmentTable.totalUnits] = totalUnits
        }.value
        AssignmentGroupTable.batchInsert(groupIds) { groupId ->
            this[AssignmentGroupTable.assignmentId] = assignmentId
            this[AssignmentGroupTable.groupId] = groupId
        }
        items.forEachIndexed { index, item ->
            AssignmentItemTable.insert {
                it[AssignmentItemTable.assignmentId] = assignmentId
                it[position] = index
                it[kind] = item.kind
                it[title] = item.title
                it[task] = item.task
                it[responseType] = item.responseType
                it[documentId] = item.documentId
                it[documentRevision] = item.documentRevision
                it[blocks] = item.blocks
                it[materialId] = item.materialId
            }
        }
        recipients.forEach { studentId ->
            val homeworkId = HomeworkTable.insertAndGetId {
                it[HomeworkTable.assignmentId] = assignmentId
                it[HomeworkTable.studentId] = studentId
            }.value
            notificationService.createNotification(studentId, teacherId, NotificationType.HOMEWORK_ASSIGNED, homeworkId)
        }

        detail(views.requireAssignment(assignmentId, principal), principal)
    }

    fun get(assignmentId: UUID, principal: UserPrincipal): AssignmentResponse = transaction {
        detail(views.requireAssignment(assignmentId, principal), principal)
    }

    fun list(principal: UserPrincipal, studentId: UUID?, groupId: UUID?, lessonId: UUID?, page: PageRequest): AssignmentPageResponse = transaction {
        val query: Query = when (principal.role) {
            UserRole.ADMIN -> AssignmentTable.selectAll()
            UserRole.TEACHER -> AssignmentTable.selectAll().where { AssignmentTable.teacherId eq principal.id }
            UserRole.STUDENT -> throw ForbiddenException("Only teachers manage assignments")
        }
        studentId?.let { id ->
            query.andWhere { AssignmentTable.id inSubQuery HomeworkTable.select(HomeworkTable.assignmentId).where { HomeworkTable.studentId eq id } }
        }
        groupId?.let { id ->
            query.andWhere { AssignmentTable.id inSubQuery AssignmentGroupTable.select(AssignmentGroupTable.assignmentId).where { AssignmentGroupTable.groupId eq id } }
        }
        lessonId?.let { id -> query.andWhere { AssignmentTable.lessonId eq id } }

        val total = query.count()
        val rows = query.orderBy(AssignmentTable.createdAt to SortOrder.DESC)
            .limit(page.pageSize).offset(page.offset).toList()
        val ids = rows.map { it[AssignmentTable.id].value }
        val groups = views.groupIds(ids)
        val count = HomeworkTable.id.count()
        val counts = if (ids.isEmpty()) emptyMap() else HomeworkTable
            .select(HomeworkTable.assignmentId, HomeworkTable.status, count)
            .where { HomeworkTable.assignmentId inList ids }
            .groupBy(HomeworkTable.assignmentId, HomeworkTable.status)
            .groupBy({ it[HomeworkTable.assignmentId].value }) { it[HomeworkTable.status] to it[count].toInt() }

        AssignmentPageResponse(
            assignments = rows.map { row ->
                val id = row[AssignmentTable.id].value
                val statusCounts = HomeworkStatus.entries.associateWith { 0 } + counts[id].orEmpty().toMap()
                AssignmentSummary(
                    id = id.toString(),
                    teacherId = row[AssignmentTable.teacherId].value.toString(),
                    title = row[AssignmentTable.title],
                    instructions = row[AssignmentTable.instructions],
                    dueDate = row[AssignmentTable.dueDate]?.toString(),
                    lessonId = row[AssignmentTable.lessonId]?.value?.toString(),
                    groupIds = groups[id].orEmpty(),
                    createdAt = row[AssignmentTable.createdAt],
                    updatedAt = row[AssignmentTable.updatedAt],
                    itemCount = row[AssignmentTable.itemCount],
                    studentCount = statusCounts.values.sum(),
                    statusCounts = statusCounts,
                )
            },
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun update(assignmentId: UUID, request: UpdateAssignmentRequest, principal: UserPrincipal): AssignmentResponse = transaction {
        views.requireAssignment(assignmentId, principal)
        if (principal.role != UserRole.TEACHER) throw ForbiddenException("Only the teacher can change an assignment")
        val dueDate = request.dueDate?.let(::parseDate)
        AssignmentTable.update({ AssignmentTable.id eq assignmentId }) {
            request.title?.let { title -> it[AssignmentTable.title] = title.trim() }
            request.instructions?.let { text -> it[instructions] = text.takeIf(String::isNotBlank) }
            if (request.clearDueDate) it[AssignmentTable.dueDate] = null
            else if (dueDate != null) it[AssignmentTable.dueDate] = dueDate
        }
        detail(views.requireAssignment(assignmentId, principal), principal)
    }

    fun delete(assignmentId: UUID, principal: UserPrincipal) {
        val keys = transaction {
            views.requireAssignment(assignmentId, principal)
            val keys = HomeworkUploadTable.innerJoin(HomeworkTable)
                .select(HomeworkUploadTable.storageKey)
                .where { HomeworkTable.assignmentId eq assignmentId }
                .map { it[HomeworkUploadTable.storageKey] }
            AssignmentTable.deleteWhere { AssignmentTable.id eq assignmentId }
            keys
        }
        keys.forEach(storage::delete)
    }

    private fun detail(row: ResultRow, principal: UserPrincipal): AssignmentResponse {
        val id = row[AssignmentTable.id].value
        val homework = HomeworkTable.innerJoin(AssignmentTable).selectAll()
            .where { HomeworkTable.assignmentId eq id }
            .orderBy(HomeworkTable.createdAt to SortOrder.ASC)
            .toList()
        return AssignmentResponse(
            id = id.toString(),
            teacherId = row[AssignmentTable.teacherId].value.toString(),
            title = row[AssignmentTable.title],
            instructions = row[AssignmentTable.instructions],
            dueDate = row[AssignmentTable.dueDate]?.toString(),
            lessonId = row[AssignmentTable.lessonId]?.value?.toString(),
            groupIds = views.groupIds(listOf(id))[id].orEmpty(),
            createdAt = row[AssignmentTable.createdAt],
            updatedAt = row[AssignmentTable.updatedAt],
            items = views.itemResponses(
                views.itemRows(listOf(id))[id].orEmpty(), principal,
                stripSolutions = false,
                teacherId = row[AssignmentTable.teacherId].value,
                lessonId = row[AssignmentTable.lessonId]?.value,
            ),
            homework = views.summaries(homework, principal),
        )
    }

    private data class ResolvedItem(
        val kind: AssignmentItemKind,
        val title: String,
        val task: String?,
        val responseType: HomeworkResponseType?,
        val documentId: UUID? = null,
        val documentRevision: Int? = null,
        val blocks: JsonArray? = null,
        val materialId: UUID? = null,
    )

    private fun resolveItem(input: AssignmentItemInput, index: Int, teacherId: UUID): ResolvedItem {
        val pointer = "/items/$index"
        fun invalid(field: String, message: String): Nothing =
            throw BadRequestException("Invalid item at $pointer/$field: $message", code = "HOMEWORK_ITEM_INVALID", pointer = "$pointer/$field")
        fun notAllowed(field: String, value: Any?) { if (value != null) invalid(field, "not allowed for ${input.kind}") }

        input.title?.let { if (it.length > 255) invalid("title", "longer than 255 characters") }
        input.task?.let { if (it.length > MAX_TASK) invalid("task", "longer than $MAX_TASK characters") }
        val task = input.task?.takeIf(String::isNotBlank)
        val title = input.title?.trim()?.takeIf(String::isNotEmpty)

        return when (input.kind) {
            AssignmentItemKind.DOCUMENT -> {
                notAllowed("materialId", input.materialId)
                notAllowed("responseType", input.responseType)
                val documentId = input.documentId?.let { parseItemId(it, "$pointer/documentId") } ?: invalid("documentId", "documentId is required")
                val document = DocumentTable.selectAll()
                    .where { (DocumentTable.id eq documentId) and (DocumentTable.ownerId eq teacherId) }
                    .singleOrNull() ?: throw NotFoundException("Document not found", code = "DOCUMENT_NOT_FOUND")
                val blocks = document[DocumentTable.blocks]
                if (HomeworkUnits.documentUnits(documentId, blocks).isEmpty())
                    invalid("documentId", "the document has no interactive exercise to answer")
                ResolvedItem(
                    kind = input.kind,
                    title = title ?: document[DocumentTable.title],
                    task = task,
                    responseType = null,
                    documentId = documentId,
                    documentRevision = document[DocumentTable.revision],
                    blocks = blocks,
                )
            }
            AssignmentItemKind.MATERIAL -> {
                notAllowed("documentId", input.documentId)
                val materialId = input.materialId?.let { parseItemId(it, "$pointer/materialId") } ?: invalid("materialId", "materialId is required")
                val material = MaterialTable.selectAll()
                    .where { (MaterialTable.id eq materialId) and (MaterialTable.teacherId eq teacherId) }
                    .singleOrNull() ?: throw NotFoundException("Material not found", code = "MATERIAL_NOT_FOUND")
                ResolvedItem(input.kind, title ?: material[MaterialTable.name], task, input.responseType, materialId = materialId)
            }
            AssignmentItemKind.TASK -> {
                notAllowed("documentId", input.documentId)
                notAllowed("materialId", input.materialId)
                ResolvedItem(
                    kind = input.kind,
                    title = title ?: invalid("title", "title is required"),
                    task = task,
                    responseType = input.responseType ?: invalid("responseType", "responseType is required"),
                )
            }
        }
    }

    private fun requireLinkedStudents(teacherId: UUID, ids: List<UUID>): List<UUID> {
        val uuids = ids.distinct()
        if (uuids.isEmpty()) return uuids
        val linked = TeacherStudentTable.select(TeacherStudentTable.studentId)
            .where { (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.studentId inList uuids) }
            .map { it[TeacherStudentTable.studentId].value }
            .toSet()
        val unlinked = uuids.filter { it !in linked }
        if (unlinked.isNotEmpty())
            throw BadRequestException("Teacher does not have a relationship with students: ${unlinked.joinToString()}", code = "STUDENT_NOT_LINKED")
        return uuids
    }

    private fun parseId(raw: String, field: String): UUID = runCatching { UUID.fromString(raw) }
        .getOrElse { throw BadRequestException("$field contains an invalid uuid", code = "VALIDATION_FAILED") }

    private fun parseItemId(raw: String, pointer: String): UUID = runCatching { UUID.fromString(raw) }
        .getOrElse { throw BadRequestException("Invalid uuid at $pointer", code = "HOMEWORK_ITEM_INVALID", pointer = pointer) }

    private fun parseDate(raw: String): LocalDate = runCatching { LocalDate.parse(raw) }
        .getOrElse { throw BadRequestException("dueDate must be YYYY-MM-DD", code = "VALIDATION_FAILED") }

    companion object {
        const val MAX_TASK = 5_000
    }
}

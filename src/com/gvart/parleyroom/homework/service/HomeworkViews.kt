package com.gvart.parleyroom.homework.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.service.DocumentSupport
import com.gvart.parleyroom.homework.data.AssignmentGroupTable
import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkAnswerTable
import com.gvart.parleyroom.homework.data.HomeworkOutcome
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.data.HomeworkUploadTable
import com.gvart.parleyroom.homework.transfer.AssignmentItemResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.HomeworkScore
import com.gvart.parleyroom.homework.transfer.HomeworkSummary
import com.gvart.parleyroom.homework.transfer.HomeworkUploadResponse
import com.gvart.parleyroom.homework.transfer.ItemMaterial
import com.gvart.parleyroom.homework.transfer.PersonRef
import com.gvart.parleyroom.homework.transfer.UnitResponse
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Access checks and response rendering for assignments and homework. Must be called inside a transaction. */
class HomeworkViews(
    private val documentSupport: DocumentSupport,
) {

    // ---- Access ----

    /** Homework joined with its assignment; anyone but its student, its teacher or an admin gets 404. */
    fun requireReadable(homeworkId: UUID, principal: UserPrincipal, forUpdate: Boolean = false): ResultRow {
        val query = HomeworkTable.innerJoin(AssignmentTable).selectAll().where { HomeworkTable.id eq homeworkId }
        val row = (if (forUpdate) query.forUpdate() else query).singleOrNull() ?: throw homeworkNotFound()
        val allowed = when (principal.role) {
            UserRole.ADMIN -> true
            UserRole.TEACHER -> row[AssignmentTable.teacherId].value == principal.id
            UserRole.STUDENT -> row[HomeworkTable.studentId].value == principal.id
        }
        if (!allowed) throw homeworkNotFound()
        return row
    }

    /** Student-side writes: only the assigned student (teachers/admins who can read it get 403). */
    fun requireOwnHomework(homeworkId: UUID, principal: UserPrincipal): ResultRow {
        val row = requireReadable(homeworkId, principal, forUpdate = true)
        if (principal.role != UserRole.STUDENT) throw ForbiddenException("Only the assigned student can answer homework")
        return row
    }

    /** Teacher-side writes: the assignment's teacher or an admin. */
    fun requireTeacherOf(homeworkId: UUID, principal: UserPrincipal): ResultRow {
        val row = requireReadable(homeworkId, principal, forUpdate = true)
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Only the teacher can review homework")
        return row
    }

    fun requireAssignment(assignmentId: UUID, principal: UserPrincipal): ResultRow {
        val row = AssignmentTable.selectAll().where { AssignmentTable.id eq assignmentId }.singleOrNull()
            ?: throw assignmentNotFound()
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> if (row[AssignmentTable.teacherId].value != principal.id) throw assignmentNotFound()
            UserRole.STUDENT -> throw ForbiddenException("Only teachers manage assignments")
        }
        return row
    }

    fun homeworkNotFound() = NotFoundException("Homework not found", code = "HOMEWORK_NOT_FOUND")
    fun assignmentNotFound() = NotFoundException("Assignment not found", code = "ASSIGNMENT_NOT_FOUND")

    // ---- Items and units ----

    fun itemRows(assignmentIds: Collection<UUID>): Map<UUID, List<ResultRow>> {
        if (assignmentIds.isEmpty()) return emptyMap()
        return AssignmentItemTable.selectAll()
            .where { AssignmentItemTable.assignmentId inList assignmentIds }
            .orderBy(AssignmentItemTable.position to SortOrder.ASC)
            .groupBy { it[AssignmentItemTable.assignmentId].value }
    }

    fun units(itemRows: List<ResultRow>): List<HomeworkUnit> = HomeworkUnits.units(itemRows.map(::itemSource))

    fun itemSource(row: ResultRow) = ItemSource(
        id = row[AssignmentItemTable.id].value,
        kind = row[AssignmentItemTable.kind],
        responseType = row[AssignmentItemTable.responseType],
        blocks = row[AssignmentItemTable.blocks],
    )

    /** Finds the unit an answer/review addresses, or 400 HOMEWORK_ITEM_INVALID. */
    fun unitFor(units: Map<UnitKey, HomeworkUnit>, assignmentItemId: String, blockId: String?, itemId: String?, pointer: String): HomeworkUnit {
        val key = runCatching {
            UnitKey(UUID.fromString(assignmentItemId), blockId?.let(UUID::fromString), itemId?.let(UUID::fromString))
        }.getOrNull()
        return key?.let { units[it] }
            ?: throw BadRequestException("No answerable unit at $pointer", code = "HOMEWORK_ITEM_INVALID", pointer = pointer)
    }

    fun itemResponses(
        rows: List<ResultRow>,
        principal: UserPrincipal,
        stripSolutions: Boolean,
        teacherId: UUID,
        lessonId: UUID?,
    ): List<AssignmentItemResponse> {
        val materialIds = rows.mapNotNull { it[AssignmentItemTable.materialId]?.value }
        val materials = if (materialIds.isEmpty()) emptyMap() else MaterialTable.selectAll()
            .where { MaterialTable.id inList materialIds }
            .associateBy { it[MaterialTable.id].value }
        return rows.map { row ->
            val blocks = row[AssignmentItemTable.blocks]
            val material = row[AssignmentItemTable.materialId]?.value?.let(materials::get)
            AssignmentItemResponse(
                id = row[AssignmentItemTable.id].value.toString(),
                position = row[AssignmentItemTable.position],
                kind = row[AssignmentItemTable.kind],
                title = row[AssignmentItemTable.title],
                task = row[AssignmentItemTable.task],
                responseType = row[AssignmentItemTable.responseType],
                documentId = row[AssignmentItemTable.documentId]?.value?.toString(),
                documentRevision = row[AssignmentItemTable.documentRevision],
                blocks = blocks?.let { if (stripSolutions) documentSupport.stripSolutions(it) else it },
                vocab = blocks?.let { documentSupport.renderVocab(it, teacherId, lessonId, principal) },
                materialId = row[AssignmentItemTable.materialId]?.value?.toString(),
                material = material?.let(::itemMaterial),
            )
        }
    }

    private fun itemMaterial(row: ResultRow): ItemMaterial {
        val id = row[MaterialTable.id].value
        val type = row[MaterialTable.type]
        val url = row[MaterialTable.url]
        return ItemMaterial(
            id = id.toString(),
            name = row[MaterialTable.name],
            type = type,
            contentType = row[MaterialTable.contentType],
            downloadUrl = when {
                type == MaterialType.LINK -> url
                url.isBlank() -> null
                else -> "/api/v1/materials/$id/file"
            },
        )
    }

    // ---- Homework ----

    fun groupIds(assignmentIds: Collection<UUID>): Map<UUID, List<String>> {
        if (assignmentIds.isEmpty()) return emptyMap()
        return AssignmentGroupTable.selectAll()
            .where { AssignmentGroupTable.assignmentId inList assignmentIds }
            .groupBy({ it[AssignmentGroupTable.assignmentId].value }) { it[AssignmentGroupTable.groupId].value.toString() }
    }

    /** Summaries for rows of HomeworkTable joined with AssignmentTable. */
    fun summaries(rows: List<ResultRow>, principal: UserPrincipal): List<HomeworkSummary> {
        if (rows.isEmpty()) return emptyList()
        val homeworkIds = rows.map { it[HomeworkTable.id].value }
        val people = people(rows.flatMap { listOf(it[HomeworkTable.studentId].value, it[AssignmentTable.teacherId].value) })
        val answered = answeredCounts(homeworkIds)
        return rows.map { row -> summary(row, principal, people, answered[row[HomeworkTable.id].value] ?: 0) }
    }

    fun detail(row: ResultRow, principal: UserPrincipal): HomeworkResponse {
        val homeworkId = row[HomeworkTable.id].value
        val status = row[HomeworkTable.status]
        val isStudent = principal.role == UserRole.STUDENT
        val revealResults = status == HomeworkStatus.REVIEWED || status == HomeworkStatus.DONE
        val revealFeedback = revealResults || (status == HomeworkStatus.OPEN && row[HomeworkTable.lastOutcome] == HomeworkOutcome.RETURNED)
        // Drafts are private: teachers see content only once it was submitted.
        val hideContent = !isStudent && status == HomeworkStatus.OPEN
        val showResults = !isStudent || revealResults
        val showFeedback = !isStudent || revealFeedback

        val itemRows = itemRows(listOf(row[AssignmentTable.id].value))[row[AssignmentTable.id].value].orEmpty()
        val answers = HomeworkAnswerTable.selectAll()
            .where { HomeworkAnswerTable.homeworkId eq homeworkId }
            .associateBy { UnitKey(it[HomeworkAnswerTable.assignmentItemId].value, it[HomeworkAnswerTable.blockId], it[HomeworkAnswerTable.itemRef]) }

        val units = units(itemRows).map { unit ->
            val answer = answers[unit.key]
            val autoResult = answer?.get(HomeworkAnswerTable.autoResult)
            val teacherCorrect = answer?.get(HomeworkAnswerTable.teacherCorrect)
            UnitResponse(
                assignmentItemId = unit.assignmentItemId.toString(),
                blockId = unit.blockId?.toString(),
                itemId = unit.itemRef?.toString(),
                blockType = unit.blockType,
                questionKind = unit.questionKind,
                check = unit.check,
                answer = if (hideContent) null else answer?.get(HomeworkAnswerTable.answer),
                answeredAt = answer?.get(HomeworkAnswerTable.answeredAt),
                autoResult = if (showResults) autoResult else null,
                autoScore = if (showResults) answer?.get(HomeworkAnswerTable.autoScore) else null,
                caseMismatch = if (showResults) answer?.get(HomeworkAnswerTable.caseMismatch) else null,
                gapResults = if (showResults) answer?.get(HomeworkAnswerTable.gapResults) else null,
                teacherCorrect = if (showResults) teacherCorrect else null,
                correct = if (showResults) teacherCorrect ?: when (autoResult) {
                    AutoResult.CORRECT -> true
                    AutoResult.INCORRECT -> false
                    else -> null
                } else null,
                comment = if (showFeedback) answer?.get(HomeworkAnswerTable.comment) else null,
            )
        }

        val uploads = if (hideContent) emptyList() else HomeworkUploadTable.selectAll()
            .where { HomeworkUploadTable.homeworkId eq homeworkId }
            .orderBy(HomeworkUploadTable.createdAt to SortOrder.ASC)
            .map { upload ->
                val id = upload[HomeworkUploadTable.id].value
                HomeworkUploadResponse(
                    id = id.toString(),
                    assignmentItemId = upload[HomeworkUploadTable.assignmentItemId].value.toString(),
                    fileName = upload[HomeworkUploadTable.fileName],
                    contentType = upload[HomeworkUploadTable.contentType],
                    size = upload[HomeworkUploadTable.size],
                    downloadUrl = "/api/v1/homework/$homeworkId/uploads/$id/file",
                    createdAt = upload[HomeworkUploadTable.createdAt],
                )
            }

        val summary = summary(
            row, principal,
            people(listOf(row[HomeworkTable.studentId].value, row[AssignmentTable.teacherId].value)),
            answers.values.count { it[HomeworkAnswerTable.answeredAt] != null },
        )
        return HomeworkResponse(
            id = summary.id,
            assignmentId = summary.assignmentId,
            title = summary.title,
            dueDate = summary.dueDate,
            lessonId = summary.lessonId,
            status = summary.status,
            lastOutcome = summary.lastOutcome,
            attempt = summary.attempt,
            itemCount = summary.itemCount,
            student = summary.student,
            teacher = summary.teacher,
            answeredUnits = summary.answeredUnits,
            totalUnits = summary.totalUnits,
            lastSavedAt = summary.lastSavedAt,
            summary = summary.summary,
            submittedAt = summary.submittedAt,
            reviewedAt = summary.reviewedAt,
            returnedAt = summary.returnedAt,
            doneAt = summary.doneAt,
            createdAt = summary.createdAt,
            updatedAt = summary.updatedAt,
            instructions = row[AssignmentTable.instructions],
            feedback = if (showFeedback) row[HomeworkTable.feedback] else null,
            items = itemResponses(
                itemRows, principal,
                stripSolutions = isStudent && !revealResults,
                teacherId = row[AssignmentTable.teacherId].value,
                lessonId = row[AssignmentTable.lessonId]?.value,
            ),
            units = units,
            uploads = uploads,
        )
    }

    fun answeredCounts(homeworkIds: List<UUID>): Map<UUID, Int> {
        if (homeworkIds.isEmpty()) return emptyMap()
        val count = HomeworkAnswerTable.id.count()
        return HomeworkAnswerTable.select(HomeworkAnswerTable.homeworkId, count)
            .where { (HomeworkAnswerTable.homeworkId inList homeworkIds) and HomeworkAnswerTable.answeredAt.isNotNull() }
            .groupBy(HomeworkAnswerTable.homeworkId)
            .associate { it[HomeworkAnswerTable.homeworkId].value to it[count].toInt() }
    }

    private fun summary(row: ResultRow, principal: UserPrincipal, people: Map<UUID, PersonRef>, answeredUnits: Int): HomeworkSummary {
        val status = row[HomeworkTable.status]
        val revealScore = principal.role != UserRole.STUDENT || status == HomeworkStatus.REVIEWED || status == HomeworkStatus.DONE
        val closedTotal = row[HomeworkTable.closedTotal]
        return HomeworkSummary(
            id = row[HomeworkTable.id].value.toString(),
            assignmentId = row[AssignmentTable.id].value.toString(),
            title = row[AssignmentTable.title],
            dueDate = row[AssignmentTable.dueDate]?.toString(),
            lessonId = row[AssignmentTable.lessonId]?.value?.toString(),
            status = status,
            lastOutcome = row[HomeworkTable.lastOutcome],
            attempt = row[HomeworkTable.attempt],
            itemCount = row[AssignmentTable.itemCount],
            student = people.getValue(row[HomeworkTable.studentId].value),
            teacher = people.getValue(row[AssignmentTable.teacherId].value),
            answeredUnits = answeredUnits,
            totalUnits = row[AssignmentTable.totalUnits],
            lastSavedAt = row[HomeworkTable.lastSavedAt],
            summary = if (revealScore && closedTotal != null) HomeworkScore(
                closedCorrect = row[HomeworkTable.closedCorrect] ?: 0,
                closedTotal = closedTotal,
                pendingReview = row[HomeworkTable.pendingReview] ?: 0,
                unanswered = row[HomeworkTable.unanswered] ?: 0,
            ) else null,
            submittedAt = row[HomeworkTable.submittedAt],
            reviewedAt = row[HomeworkTable.reviewedAt],
            returnedAt = row[HomeworkTable.returnedAt],
            doneAt = row[HomeworkTable.doneAt],
            createdAt = row[HomeworkTable.createdAt],
            updatedAt = row[HomeworkTable.updatedAt],
        )
    }

    private fun people(ids: List<UUID>): Map<UUID, PersonRef> =
        UserTable.select(UserTable.id, UserTable.firstName, UserTable.lastName)
            .where { UserTable.id inList ids.distinct() }
            .associate {
                it[UserTable.id].value to PersonRef(it[UserTable.id].value.toString(), it[UserTable.firstName], it[UserTable.lastName])
            }
}

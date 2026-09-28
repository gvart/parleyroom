package com.gvart.parleyroom.homework.service

import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.service.LearningActivityRecorder
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkAnswerTable
import com.gvart.parleyroom.homework.data.HomeworkOutcome
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.data.HomeworkUploadTable
import com.gvart.parleyroom.homework.transfer.AnswersSavedResponse
import com.gvart.parleyroom.homework.transfer.HomeworkCountsResponse
import com.gvart.parleyroom.homework.transfer.HomeworkPageResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.ReviewUnitInput
import com.gvart.parleyroom.homework.transfer.SaveAnswersRequest
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** Per-student homework: answers (drafts), submit with auto-check, teacher review, lists and counts. */
class HomeworkService(
    private val views: HomeworkViews,
    private val notificationService: NotificationService,
    private val storage: StorageService,
) {

    enum class Sort { DUE, SUBMITTED, CREATED }

    data class ListFilter(
        val studentId: UUID? = null,
        val assignmentId: UUID? = null,
        /** Source document of a DOCUMENT item (the snapshot keeps it). */
        val documentId: UUID? = null,
        val lessonId: UUID? = null,
        val statuses: List<HomeworkStatus> = emptyList(),
        val dueBefore: LocalDate? = null,
        val dueAfter: LocalDate? = null,
        val sort: Sort? = null,
    )

    fun list(principal: UserPrincipal, filter: ListFilter, page: PageRequest): HomeworkPageResponse = transaction {
        val query = HomeworkTable.innerJoin(AssignmentTable).selectAll()
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> query.where { AssignmentTable.teacherId eq principal.id }
            UserRole.STUDENT -> query.where { HomeworkTable.studentId eq principal.id }
        }
        filter.studentId?.let { id ->
            AuthorizationHelper.requireAccessToStudent(id, principal)
            query.andWhere { HomeworkTable.studentId eq id }
        }
        filter.assignmentId?.let { id -> query.andWhere { HomeworkTable.assignmentId eq id } }
        filter.documentId?.let { id ->
            query.andWhere {
                HomeworkTable.assignmentId inSubQuery AssignmentItemTable.select(AssignmentItemTable.assignmentId)
                    .where { AssignmentItemTable.documentId eq id }
            }
        }
        filter.lessonId?.let { id -> query.andWhere { AssignmentTable.lessonId eq id } }
        if (filter.statuses.isNotEmpty()) query.andWhere { HomeworkTable.status inList filter.statuses }
        filter.dueBefore?.let { date -> query.andWhere { AssignmentTable.dueDate lessEq date } }
        filter.dueAfter?.let { date -> query.andWhere { AssignmentTable.dueDate greaterEq date } }

        val total = query.count()
        val sort = filter.sort ?: if (principal.role == UserRole.STUDENT) Sort.DUE else Sort.CREATED
        val ordered = when (sort) {
            Sort.DUE -> query.orderBy(AssignmentTable.dueDate to SortOrder.ASC_NULLS_LAST, HomeworkTable.createdAt to SortOrder.DESC)
            Sort.SUBMITTED -> query.orderBy(HomeworkTable.submittedAt to SortOrder.ASC_NULLS_LAST, HomeworkTable.createdAt to SortOrder.ASC)
            Sort.CREATED -> query.orderBy(HomeworkTable.createdAt to SortOrder.DESC)
        }
        val rows = ordered.orderBy(HomeworkTable.id to SortOrder.ASC).limit(page.pageSize).offset(page.offset).toList()
        HomeworkPageResponse(views.summaries(rows, principal), total, page.page, page.pageSize)
    }

    /** Dashboard counts in one query; "today" is the caller's local date. */
    fun counts(principal: UserPrincipal): HomeworkCountsResponse = transaction {
        val zone = UserTable.select(UserTable.timezone).where { UserTable.id eq principal.id }.singleOrNull()
            ?.let { runCatching { ZoneId.of(it[UserTable.timezone]) }.getOrNull() } ?: ZoneId.of("Europe/Berlin")
        val today = java.time.LocalDate.now(zone)
        if (principal.role == UserRole.STUDENT) {
            val (open, dueSoon, returned) = Sql.rows(
                """
                SELECT count(*) FILTER (WHERE h.status = 'OPEN'),
                       count(*) FILTER (WHERE h.status = 'OPEN' AND a.due_date <= ?::date),
                       count(*) FILTER (WHERE h.status = 'OPEN' AND h.last_outcome = 'RETURNED')
                FROM homework h JOIN assignments a ON a.id = h.assignment_id
                WHERE h.student_id = ?
                """.trimIndent(),
                today.plusDays(2).toString(), principal.id,
            ) { rs -> Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }.single()
            return@transaction HomeworkCountsResponse(open = open, dueSoon = dueSoon, returned = returned)
        }
        val select = """
            SELECT count(*) FILTER (WHERE h.status = 'SUBMITTED'),
                   count(*) FILTER (WHERE h.status = 'OPEN' AND a.due_date < ?::date),
                   count(*) FILTER (WHERE h.status = 'OPEN')
            FROM homework h JOIN assignments a ON a.id = h.assignment_id
        """.trimIndent()
        val read = { rs: java.sql.ResultSet -> Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)) }
        val (toReview, overdue, openTotal) = if (principal.role == UserRole.ADMIN) Sql.rows(select, today.toString(), read = read).single()
        else Sql.rows("$select WHERE a.teacher_id = ?", today.toString(), principal.id, read = read).single()
        HomeworkCountsResponse(toReview = toReview, overdue = overdue, openTotal = openTotal)
    }

    fun get(homeworkId: UUID, principal: UserPrincipal): HomeworkResponse = transaction {
        views.detail(views.requireReadable(homeworkId, principal), principal)
    }

    /** Partial, idempotent merge of the listed units' answers (the autosave target). Never submits. */
    fun saveAnswers(homeworkId: UUID, request: SaveAnswersRequest, principal: UserPrincipal): AnswersSavedResponse = transaction {
        val row = views.requireOwnHomework(homeworkId, principal)
        requireOpen(row)
        val units = unitsOf(row)
        val existing = answerRows(homeworkId)
        val now = OffsetDateTime.now()

        request.answers.forEachIndexed { index, input ->
            val pointer = "/answers/$index"
            val unit = views.unitFor(units, input.assignmentItemId, input.blockId, input.itemId, pointer)
            val answer = input.answer
            if (answer != null) {
                val uploadIds = HomeworkUnits.validateAnswer(unit, answer, "$pointer/answer")
                if (uploadIds.isNotEmpty()) {
                    val known = HomeworkUploadTable.select(HomeworkUploadTable.id)
                        .where {
                            (HomeworkUploadTable.homeworkId eq homeworkId) and
                                    (HomeworkUploadTable.assignmentItemId eq unit.assignmentItemId) and
                                    (HomeworkUploadTable.id inList uploadIds)
                        }
                        .count()
                    if (known.toInt() != uploadIds.size)
                        throw BadRequestException("Unknown upload id", code = "HOMEWORK_ANSWER_INVALID", pointer = "$pointer/answer/uploadIds")
                }
            }
            val current = existing[unit.key]
            if (current?.get(HomeworkAnswerTable.answer) == answer) return@forEachIndexed
            val answeredAt = if (HomeworkUnits.isAnswered(unit, answer)) now else null
            if (current == null) {
                if (answer == null) return@forEachIndexed
                HomeworkAnswerTable.insert {
                    it[HomeworkAnswerTable.homeworkId] = homeworkId
                    it[assignmentItemId] = unit.assignmentItemId
                    it[blockId] = unit.blockId
                    it[itemRef] = unit.itemRef
                    it[HomeworkAnswerTable.answer] = answer
                    it[HomeworkAnswerTable.answeredAt] = answeredAt
                }
            } else {
                // A changed answer invalidates the previous attempt's auto result (recomputed on submit).
                HomeworkAnswerTable.update({ HomeworkAnswerTable.id eq current[HomeworkAnswerTable.id] }) {
                    it[HomeworkAnswerTable.answer] = answer
                    it[HomeworkAnswerTable.answeredAt] = answeredAt
                    it[autoResult] = null
                    it[autoScore] = null
                    it[caseMismatch] = false
                    it[gapResults] = null
                }
            }
        }

        HomeworkTable.update({ HomeworkTable.id eq homeworkId }) { it[lastSavedAt] = now }
        val saved = HomeworkTable.selectAll().where { HomeworkTable.id eq homeworkId }.single()
        AnswersSavedResponse(
            updatedAt = saved[HomeworkTable.updatedAt],
            lastSavedAt = saved[HomeworkTable.lastSavedAt],
            answeredUnits = views.answeredCounts(listOf(homeworkId))[homeworkId] ?: 0,
            totalUnits = row[AssignmentTable.totalUnits],
        )
    }

    /** Runs the auto-check over every unit, locks the answers and notifies the teacher. */
    fun submit(homeworkId: UUID, principal: UserPrincipal): HomeworkResponse = transaction {
        val row = views.requireOwnHomework(homeworkId, principal)
        requireOpen(row)
        val resubmit = row[HomeworkTable.attempt] > 0
        val existing = answerRows(homeworkId)
        var closedCorrect = 0
        var closedTotal = 0
        var pendingReview = 0
        var unanswered = 0

        unitsOf(row).values.forEach { unit ->
            val current = existing[unit.key]
            val answer = current?.get(HomeworkAnswerTable.answer)
            val result = HomeworkUnits.check(unit, answer)
            when (result.autoResult) {
                AutoResult.CORRECT -> { closedCorrect++; closedTotal++ }
                AutoResult.INCORRECT -> closedTotal++
                AutoResult.PENDING_REVIEW -> pendingReview++
                AutoResult.UNANSWERED -> Unit
            }
            if (!HomeworkUnits.isAnswered(unit, answer)) unanswered++

            if (current == null) {
                HomeworkAnswerTable.insert {
                    it[HomeworkAnswerTable.homeworkId] = homeworkId
                    it[assignmentItemId] = unit.assignmentItemId
                    it[blockId] = unit.blockId
                    it[itemRef] = unit.itemRef
                    it[autoResult] = result.autoResult
                    it[autoScore] = result.score
                    it[caseMismatch] = result.caseMismatch
                    it[gapResults] = result.gapResults
                }
            } else {
                val changed = resubmit && current[HomeworkAnswerTable.submittedAnswer] != answer
                HomeworkAnswerTable.update({ HomeworkAnswerTable.id eq current[HomeworkAnswerTable.id] }) {
                    it[submittedAnswer] = answer
                    it[autoResult] = result.autoResult
                    it[autoScore] = result.score
                    it[caseMismatch] = result.caseMismatch
                    it[gapResults] = result.gapResults
                    if (changed) {
                        it[teacherCorrect] = null
                        it[comment] = null
                    }
                }
            }
        }

        HomeworkTable.update({ HomeworkTable.id eq homeworkId }) {
            it[status] = HomeworkStatus.SUBMITTED
            it[attempt] = row[HomeworkTable.attempt] + 1
            it[submittedAt] = OffsetDateTime.now()
            it[HomeworkTable.closedCorrect] = closedCorrect
            it[HomeworkTable.closedTotal] = closedTotal
            it[HomeworkTable.pendingReview] = pendingReview
            it[HomeworkTable.unanswered] = unanswered
        }
        LearningActivityRecorder.record(principal.id, ActivityKind.HOMEWORK_SUBMITTED, homeworkId)
        notificationService.createNotification(row[AssignmentTable.teacherId].value, principal.id, NotificationType.HOMEWORK_SUBMITTED, homeworkId)

        views.detail(views.requireReadable(homeworkId, principal), principal)
    }

    /** Teacher autosave of the review: SUBMITTED only, no status change, not visible to the student. */
    fun saveReview(homeworkId: UUID, feedback: String?, units: List<ReviewUnitInput>, principal: UserPrincipal): HomeworkResponse = transaction {
        val row = views.requireTeacherOf(homeworkId, principal)
        if (row[HomeworkTable.status] != HomeworkStatus.SUBMITTED)
            throw ConflictException("Only submitted homework can be reviewed", code = "HOMEWORK_INVALID_STATE")
        applyReview(row, feedback, units)
        views.detail(views.requireReadable(homeworkId, principal), principal)
    }

    fun review(homeworkId: UUID, feedback: String?, units: List<ReviewUnitInput>, outcome: HomeworkOutcome, principal: UserPrincipal): HomeworkResponse = transaction {
        val row = views.requireTeacherOf(homeworkId, principal)
        val previous = row[HomeworkTable.status]
        if (previous != HomeworkStatus.SUBMITTED && previous != HomeworkStatus.REVIEWED)
            throw ConflictException("Homework in status $previous cannot be reviewed", code = "HOMEWORK_INVALID_STATE")
        applyReview(row, feedback, units)

        val now = OffsetDateTime.now()
        HomeworkTable.update({ HomeworkTable.id eq homeworkId }) {
            it[lastOutcome] = outcome
            when (outcome) {
                HomeworkOutcome.REVIEWED -> {
                    it[status] = HomeworkStatus.REVIEWED
                    it[reviewedAt] = now
                }
                HomeworkOutcome.RETURNED -> {
                    it[status] = HomeworkStatus.OPEN
                    it[returnedAt] = now
                }
                HomeworkOutcome.DONE -> {
                    it[status] = HomeworkStatus.DONE
                    it[doneAt] = now
                    if (row[HomeworkTable.reviewedAt] == null) it[reviewedAt] = now
                }
            }
        }
        val type = when (outcome) {
            HomeworkOutcome.RETURNED -> NotificationType.HOMEWORK_RETURNED
            HomeworkOutcome.REVIEWED -> NotificationType.HOMEWORK_REVIEWED
            // Closing an already reviewed homework tells the student nothing new.
            HomeworkOutcome.DONE -> if (previous == HomeworkStatus.SUBMITTED) NotificationType.HOMEWORK_REVIEWED else null
        }
        type?.let { notificationService.createNotification(row[HomeworkTable.studentId].value, principal.id, it, homeworkId) }

        views.detail(views.requireReadable(homeworkId, principal), principal)
    }

    /** Removes one student's homework (the assignment's teacher or an admin). */
    fun delete(homeworkId: UUID, principal: UserPrincipal) {
        val keys = transaction {
            views.requireTeacherOf(homeworkId, principal)
            val keys = HomeworkUploadTable.select(HomeworkUploadTable.storageKey)
                .where { HomeworkUploadTable.homeworkId eq homeworkId }
                .map { it[HomeworkUploadTable.storageKey] }
            HomeworkTable.deleteWhere { HomeworkTable.id eq homeworkId }
            keys
        }
        keys.forEach(storage::delete)
    }

    private fun applyReview(row: ResultRow, feedback: String?, units: List<ReviewUnitInput>) {
        val homeworkId = row[HomeworkTable.id].value
        val known = unitsOf(row)
        val existing = answerRows(homeworkId)
        units.forEachIndexed { index, input ->
            val unit = views.unitFor(known, input.assignmentItemId, input.blockId, input.itemId, "/units/$index")
            val current = existing[unit.key]
            if (current == null) {
                HomeworkAnswerTable.insert {
                    it[HomeworkAnswerTable.homeworkId] = homeworkId
                    it[assignmentItemId] = unit.assignmentItemId
                    it[blockId] = unit.blockId
                    it[itemRef] = unit.itemRef
                    it[teacherCorrect] = input.correct
                    it[comment] = input.comment?.takeIf(String::isNotBlank)
                }
            } else {
                HomeworkAnswerTable.update({ HomeworkAnswerTable.id eq current[HomeworkAnswerTable.id] }) {
                    it[teacherCorrect] = input.correct
                    it[comment] = input.comment?.takeIf(String::isNotBlank)
                }
            }
        }
        HomeworkTable.update({ HomeworkTable.id eq homeworkId }) {
            it[HomeworkTable.feedback] = feedback?.takeIf(String::isNotBlank)
        }
    }

    private fun requireOpen(row: ResultRow) {
        if (row[HomeworkTable.status] != HomeworkStatus.OPEN)
            throw ConflictException("Homework is ${row[HomeworkTable.status]} and cannot be changed", code = "SUBMISSION_LOCKED")
    }

    private fun unitsOf(row: ResultRow): Map<UnitKey, HomeworkUnit> {
        val assignmentId = row[AssignmentTable.id].value
        return views.units(views.itemRows(listOf(assignmentId))[assignmentId].orEmpty()).associateBy { it.key }
    }

    private fun answerRows(homeworkId: UUID): Map<UnitKey, ResultRow> = HomeworkAnswerTable.selectAll()
        .where { HomeworkAnswerTable.homeworkId eq homeworkId }
        .associateBy { UnitKey(it[HomeworkAnswerTable.assignmentItemId].value, it[HomeworkAnswerTable.blockId], it[HomeworkAnswerTable.itemRef]) }
}

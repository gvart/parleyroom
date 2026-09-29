package com.gvart.parleyroom.homework.service

import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkAnswerTable
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.data.HomeworkUploadTable
import com.gvart.parleyroom.homework.transfer.HomeworkUploadResponse
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Audio / video / file answers of MATERIAL and TASK units, stored in object storage. */
class HomeworkUploadService(
    private val views: HomeworkViews,
    private val storage: StorageService,
) {

    data class DownloadTarget(val storageKey: String, val fileName: String, val contentType: String)

    /** Cheap checks before the request body is read, so nobody streams 100 MiB just to get a 403. */
    fun requireUploadable(homeworkId: UUID, assignmentItemId: UUID, principal: UserPrincipal) {
        transaction { uploadTarget(homeworkId, assignmentItemId, principal) }
    }

    fun upload(
        homeworkId: UUID,
        assignmentItemId: UUID,
        fileName: String,
        rawContentType: String,
        bytes: ByteArray,
        principal: UserPrincipal,
    ): HomeworkUploadResponse = transaction {
        val responseType = uploadTarget(homeworkId, assignmentItemId, principal)
        val contentType = rawContentType.substringBefore(';').trim().lowercase()
        if (contentType !in ALLOWED.getValue(responseType))
            throw BadRequestException("$contentType is not allowed for $responseType answers", code = "UPLOAD_TYPE_NOT_ALLOWED")
        val count = HomeworkUploadTable.selectAll()
            .where { (HomeworkUploadTable.homeworkId eq homeworkId) and (HomeworkUploadTable.assignmentItemId eq assignmentItemId) }
            .count()
        if (count >= HomeworkUnits.MAX_UPLOADS_PER_UNIT)
            throw ConflictException("At most ${HomeworkUnits.MAX_UPLOADS_PER_UNIT} uploads per task", code = "UPLOAD_LIMIT_REACHED")

        val uploadId = UUID.randomUUID()
        val key = storage.buildHomeworkKey(homeworkId, uploadId, fileName)
        HomeworkUploadTable.insertAndGetId {
            it[id] = uploadId
            it[HomeworkUploadTable.homeworkId] = homeworkId
            it[HomeworkUploadTable.assignmentItemId] = assignmentItemId
            it[storageKey] = key
            it[HomeworkUploadTable.fileName] = fileName.take(255)
            it[HomeworkUploadTable.contentType] = contentType
            it[size] = bytes.size.toLong()
        }
        // Inside the transaction: a failed upload rolls the row back.
        storage.upload(key, contentType, bytes.inputStream(), bytes.size.toLong())
        toResponse(HomeworkUploadTable.selectAll().where { HomeworkUploadTable.id eq uploadId }.single())
    }

    /** Student, while OPEN (e.g. to re-record). The upload must belong to [assignmentItemId]. */
    fun delete(homeworkId: UUID, assignmentItemId: UUID, uploadId: UUID, principal: UserPrincipal) {
        val key = transaction {
            val row = views.requireOwnHomework(homeworkId, principal)
            if (row[HomeworkTable.status] != HomeworkStatus.OPEN)
                throw ConflictException("Homework is ${row[HomeworkTable.status]} and cannot be changed", code = "SUBMISSION_LOCKED")
            val upload = findUpload(homeworkId, uploadId)
            val itemId = upload[HomeworkUploadTable.assignmentItemId].value
            if (itemId != assignmentItemId) throw uploadNotFound()
            HomeworkAnswerTable.selectAll()
                .where {
                    (HomeworkAnswerTable.homeworkId eq homeworkId) and
                            (HomeworkAnswerTable.assignmentItemId eq itemId) and HomeworkAnswerTable.blockId.isNull()
                }
                .singleOrNull()
                ?.let { answerRow -> removeFromAnswer(answerRow, uploadId) }
            HomeworkUploadTable.deleteWhere { HomeworkUploadTable.id eq uploadId }
            upload[HomeworkUploadTable.storageKey]
        }
        storage.delete(key)
    }

    /** The student always; the teacher / admin only once the homework was submitted (drafts are private). */
    fun downloadTarget(homeworkId: UUID, uploadId: UUID, principal: UserPrincipal): DownloadTarget = transaction {
        views.requireReadable(homeworkId, principal)
        val upload = findUpload(homeworkId, uploadId)
        DownloadTarget(upload[HomeworkUploadTable.storageKey], upload[HomeworkUploadTable.fileName], upload[HomeworkUploadTable.contentType])
    }

    private fun uploadTarget(homeworkId: UUID, assignmentItemId: UUID, principal: UserPrincipal): HomeworkResponseType {
        val row = views.requireOwnHomework(homeworkId, principal)
        if (row[HomeworkTable.status] != HomeworkStatus.OPEN)
            throw ConflictException("Homework is ${row[HomeworkTable.status]} and cannot be changed", code = "SUBMISSION_LOCKED")
        val item = AssignmentItemTable.selectAll()
            .where { (AssignmentItemTable.id eq assignmentItemId) and (AssignmentItemTable.assignmentId eq row[AssignmentTable.id]) }
            .singleOrNull()
            ?: throw BadRequestException("No such item in this homework", code = "HOMEWORK_ITEM_INVALID")
        val responseType = item[AssignmentItemTable.responseType]
        if (responseType == null || responseType == HomeworkResponseType.TEXT)
            throw BadRequestException("This item takes no uploads", code = "UPLOAD_TYPE_NOT_ALLOWED")
        return responseType
    }

    private fun removeFromAnswer(answerRow: ResultRow, uploadId: UUID) {
        val answer = answerRow[HomeworkAnswerTable.answer] ?: return
        val ids = answer["uploadIds"] as? JsonArray ?: return
        val remaining = ids.filter { (it as? JsonPrimitive)?.content?.lowercase() != uploadId.toString() }
        if (remaining.size == ids.size) return
        val updated = JsonObject(answer + ("uploadIds" to JsonArray(remaining)))
        val stillAnswered = remaining.isNotEmpty() || !(updated["text"] as? JsonPrimitive)?.content.isNullOrBlank()
        HomeworkAnswerTable.update({ HomeworkAnswerTable.id eq answerRow[HomeworkAnswerTable.id] }) {
            it[HomeworkAnswerTable.answer] = updated
            if (!stillAnswered) it[answeredAt] = null
        }
    }

    private fun findUpload(homeworkId: UUID, uploadId: UUID): ResultRow = HomeworkUploadTable.selectAll()
        .where { (HomeworkUploadTable.id eq uploadId) and (HomeworkUploadTable.homeworkId eq homeworkId) }
        .singleOrNull() ?: throw uploadNotFound()

    private fun uploadNotFound() = NotFoundException("Upload not found", code = "HOMEWORK_UPLOAD_NOT_FOUND")

    private fun toResponse(row: ResultRow): HomeworkUploadResponse {
        val id = row[HomeworkUploadTable.id].value
        val homeworkId = row[HomeworkUploadTable.homeworkId].value
        return HomeworkUploadResponse(
            id = id.toString(),
            assignmentItemId = row[HomeworkUploadTable.assignmentItemId].value.toString(),
            fileName = row[HomeworkUploadTable.fileName],
            contentType = row[HomeworkUploadTable.contentType],
            size = row[HomeworkUploadTable.size],
            downloadUrl = "/api/v1/homework/$homeworkId/uploads/$id/file",
            createdAt = row[HomeworkUploadTable.createdAt],
        )
    }

    companion object {
        val ALLOWED: Map<HomeworkResponseType, Set<String>> = mapOf(
            HomeworkResponseType.AUDIO to setOf(
                "audio/webm", "audio/ogg", "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav", "audio/x-wav",
            ),
            HomeworkResponseType.VIDEO to setOf("video/webm", "video/mp4", "video/quicktime"),
            HomeworkResponseType.FILE to setOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/msword",
                "image/jpeg", "image/png", "image/webp", "image/heic",
                "text/plain",
            ),
        )
    }
}

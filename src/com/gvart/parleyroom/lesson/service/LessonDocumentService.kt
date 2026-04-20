package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.lesson.data.LessonDocumentTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.LessonDocumentField
import com.gvart.parleyroom.lesson.transfer.LessonDocumentResponse
import com.gvart.parleyroom.lesson.transfer.ReflectLessonRequest
import com.gvart.parleyroom.lesson.transfer.SyncLessonDocumentRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class LessonDocumentService(
    private val support: LessonSupport,
) {

    /**
     * Lazy-creates the lesson_documents row if it does not yet exist.
     * Called from startLesson and from sync, so notes and shared document can be
     * written in the CONFIRMED phase (before the call starts).
     */
    fun ensureDocument(lessonId: UUID): UUID = transaction {
        LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId eq lessonId }
            .singleOrNull()
            ?.get(LessonDocumentTable.id)?.value
            ?: run {
                val now = OffsetDateTime.now()
                LessonDocumentTable.insertAndGetId {
                    it[LessonDocumentTable.lessonId] = lessonId
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
            }
    }

    fun syncDocument(lessonId: UUID, request: SyncLessonDocumentRequest, principal: UserPrincipal): LessonDocumentResponse = transaction {
        val lesson = support.findLesson(lessonId)
        support.requireLessonParticipant(lessonId, lesson, principal)

        val status = lesson[LessonTable.status]
        if (status != LessonStatus.CONFIRMED && status != LessonStatus.IN_PROGRESS)
            throw BadRequestException("Can only sync document for a confirmed or in-progress lesson")

        ensureDocument(lessonId)

        val field = resolveField(request, principal)
        if (field != null) {
            assertRoleCanWrite(field, principal)
            val now = OffsetDateTime.now()
            val value = request.value ?: request.notes
            LessonDocumentTable.update({ LessonDocumentTable.lessonId eq lessonId }) {
                applyField(it, field, value)
                it[updatedAt] = now
            }
        }

        LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId eq lessonId }
            .single()
            .let(support::toDocumentResponse)
    }

    fun reflectOnLesson(lessonId: UUID, request: ReflectLessonRequest, principal: UserPrincipal): LessonDocumentResponse = transaction {
        val lesson = support.findLesson(lessonId)
        support.requireLessonParticipant(lessonId, lesson, principal)

        if (principal.role != UserRole.STUDENT)
            throw ForbiddenException("Only students can submit reflections")

        if (lesson[LessonTable.startedAt] == null)
            throw BadRequestException("Lesson has not been started yet")

        ensureDocument(lessonId)

        val now = OffsetDateTime.now()

        LessonDocumentTable.update({ LessonDocumentTable.lessonId eq lessonId }) {
            if (request.studentReflection != null) it[studentReflection] = request.studentReflection
            if (request.studentHardToday != null) it[studentHardToday] = request.studentHardToday
            it[updatedAt] = now
        }

        LessonDocumentTable.selectAll()
            .where { LessonDocumentTable.lessonId eq lessonId }
            .single()
            .let(support::toDocumentResponse)
    }

    private fun resolveField(request: SyncLessonDocumentRequest, principal: UserPrincipal): LessonDocumentField? {
        request.field?.let { raw ->
            return LessonDocumentField.fromClient(raw)
                ?: throw BadRequestException("Unknown document field: $raw")
        }
        // Legacy path: no explicit field, treat `notes` as the role-appropriate private note.
        if (request.notes == null) return null
        return when (principal.role) {
            UserRole.STUDENT -> LessonDocumentField.STUDENT_NOTES
            UserRole.TEACHER, UserRole.ADMIN -> LessonDocumentField.TEACHER_NOTES
        }
    }

    private fun assertRoleCanWrite(field: LessonDocumentField, principal: UserPrincipal) {
        val role = principal.role
        val allowed = when (field) {
            LessonDocumentField.SHARED_DOCUMENT,
            LessonDocumentField.TEACHER_NOTES,
            LessonDocumentField.TEACHER_WENT_WELL,
            LessonDocumentField.TEACHER_WORKING_ON -> role == UserRole.TEACHER || role == UserRole.ADMIN
            LessonDocumentField.STUDENT_NOTES,
            LessonDocumentField.STUDENT_REFLECTION,
            LessonDocumentField.STUDENT_HARD_TODAY -> role == UserRole.STUDENT
        }
        if (!allowed) throw ForbiddenException("Your role cannot write field ${field.name}")
    }

    private fun applyField(builder: UpdateBuilder<*>, field: LessonDocumentField, value: String?) {
        when (field) {
            LessonDocumentField.SHARED_DOCUMENT -> builder[LessonDocumentTable.sharedDocument] = value
            LessonDocumentField.TEACHER_NOTES -> builder[LessonDocumentTable.teacherNotes] = value
            LessonDocumentField.STUDENT_NOTES -> builder[LessonDocumentTable.studentNotes] = value
            LessonDocumentField.TEACHER_WENT_WELL -> builder[LessonDocumentTable.teacherWentWell] = value
            LessonDocumentField.TEACHER_WORKING_ON -> builder[LessonDocumentTable.teacherWorkingOn] = value
            LessonDocumentField.STUDENT_REFLECTION -> builder[LessonDocumentTable.studentReflection] = value
            LessonDocumentField.STUDENT_HARD_TODAY -> builder[LessonDocumentTable.studentHardToday] = value
        }
    }
}

package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.lesson.data.LessonDocumentTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.LessonDocumentResponse
import com.gvart.parleyroom.lesson.transfer.ReflectLessonRequest
import com.gvart.parleyroom.lesson.transfer.SyncLessonDocumentRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class LessonDocumentService(
    private val support: LessonSupport,
) {

    fun syncDocument(lessonId: UUID, request: SyncLessonDocumentRequest, principal: UserPrincipal): LessonDocumentResponse = transaction {
        val lesson = support.findLesson(lessonId)
        support.requireLessonParticipant(lessonId, lesson, principal)

        if (LessonDocumentTable.selectAll()
                .where { LessonDocumentTable.lessonId eq lessonId }
                .empty()
        ) throw BadRequestException("Lesson has not been started yet")

        if (lesson[LessonTable.status] != LessonStatus.IN_PROGRESS)
            throw BadRequestException("Can only sync document for an in-progress lesson")

        if (request.notes != null) {
            val now = OffsetDateTime.now()
            LessonDocumentTable.update({ LessonDocumentTable.lessonId eq lessonId }) {
                when (principal.role) {
                    UserRole.TEACHER, UserRole.ADMIN -> it[teacherNotes] = request.notes
                    UserRole.STUDENT -> it[studentNotes] = request.notes
                }
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
}

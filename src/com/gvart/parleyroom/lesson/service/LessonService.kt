package com.gvart.parleyroom.lesson.service

import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.lesson.transfer.LessonResponse
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class LessonService(
    private val support: LessonSupport,
) {

    fun getLessons(
        principal: UserPrincipal,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        page: PageRequest,
    ): LessonPageResponse = transaction {
        val baseQuery = when (principal.role) {
            UserRole.ADMIN -> LessonTable.selectAll()
            UserRole.TEACHER -> LessonTable.selectAll()
                .where { LessonTable.teacherId eq principal.id }
            UserRole.STUDENT -> LessonTable.join(
                LessonStudentTable, JoinType.INNER,
                LessonTable.id, LessonStudentTable.lessonId
            ).selectAll().where {
                (LessonStudentTable.studentId eq principal.id) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
        }

        if (from != null) baseQuery.andWhere { LessonTable.scheduledAt greaterEq from }
        if (to != null) baseQuery.andWhere { LessonTable.scheduledAt lessEq to }

        val total = baseQuery.count()
        val rows = baseQuery
            .limit(page.pageSize)
            .offset(page.offset)
            .toList()

        LessonPageResponse(
            lessons = support.toResponses(rows),
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun getLesson(lessonId: UUID, principal: UserPrincipal): LessonResponse = transaction {
        val lesson = support.findLesson(lessonId)
        support.requireLessonParticipant(lessonId, lesson, principal)
        support.toResponse(lesson)
    }
}

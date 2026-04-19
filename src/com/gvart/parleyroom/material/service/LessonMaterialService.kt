package com.gvart.parleyroom.material.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.LessonMaterialTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.transfer.AttachMaterialsRequest
import com.gvart.parleyroom.material.transfer.LessonMaterialListResponse
import com.gvart.parleyroom.material.transfer.LessonMaterialResponse
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class LessonMaterialService(
    private val materialService: MaterialService,
    private val accessResolver: MaterialAccessResolver,
    private val notificationService: NotificationService,
) {

    fun attach(lessonId: UUID, request: AttachMaterialsRequest, principal: UserPrincipal): LessonMaterialListResponse {
        if (request.materialIds.isEmpty()) throw BadRequestException("materialIds must not be empty")
        val attachedNow = mutableListOf<UUID>()
        var lessonTeacherId: UUID? = null

        transaction {
            val lessonRow = LessonTable.selectAll()
                .where { LessonTable.id eq lessonId }
                .singleOrNull() ?: throw NotFoundException("Lesson not found")
            val teacherId = lessonRow[LessonTable.teacherId].value
            lessonTeacherId = teacherId
            requireLessonEditor(teacherId, principal)

            val materialIds = request.materialIds.map(UUID::fromString)
            // Ensure every material is owned by the lesson's teacher (or admin operator).
            val ownershipOk = MaterialTable
                .select(MaterialTable.id, MaterialTable.teacherId)
                .where { MaterialTable.id inList materialIds }
                .associate { it[MaterialTable.id].value to it[MaterialTable.teacherId].value }

            if (ownershipOk.size != materialIds.size) {
                throw NotFoundException("One or more materials not found")
            }
            if (principal.role != UserRole.ADMIN) {
                ownershipOk.values.forEach {
                    if (it != principal.id)
                        throw ForbiddenException("Cannot attach another teacher's material")
                }
            } else {
                ownershipOk.values.forEach {
                    if (it != teacherId)
                        throw ForbiddenException("Material owner differs from lesson teacher")
                }
            }

            val now = OffsetDateTime.now()
            materialIds.forEach { materialId ->
                val inserted = LessonMaterialTable.insertIgnore {
                    it[LessonMaterialTable.lessonId] = lessonId
                    it[LessonMaterialTable.materialId] = materialId
                    it[attachedBy] = principal.id
                    it[attachedAt] = now
                }.insertedCount
                if (inserted > 0) attachedNow.add(materialId)
            }
        }

        if (attachedNow.isNotEmpty()) {
            val confirmedStudents = transaction {
                LessonStudentTable
                    .select(LessonStudentTable.studentId)
                    .where {
                        (LessonStudentTable.lessonId eq lessonId) and
                                (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                    }
                    .map { it[LessonStudentTable.studentId].value }
            }
            confirmedStudents.forEach { studentId ->
                notificationService.createNotification(
                    userId = studentId,
                    actorId = principal.id,
                    type = NotificationType.MATERIAL_ATTACHED_TO_LESSON,
                    referenceId = lessonId,
                )
            }
        }

        return list(lessonId, principal)
    }

    fun detach(lessonId: UUID, materialId: UUID, principal: UserPrincipal) {
        transaction {
            val lessonRow = LessonTable.selectAll()
                .where { LessonTable.id eq lessonId }
                .singleOrNull() ?: throw NotFoundException("Lesson not found")
            requireLessonEditor(lessonRow[LessonTable.teacherId].value, principal)

            LessonMaterialTable.deleteWhere {
                (LessonMaterialTable.lessonId eq lessonId) and
                        (LessonMaterialTable.materialId eq materialId)
            }
        }
    }

    fun list(lessonId: UUID, principal: UserPrincipal): LessonMaterialListResponse = transaction {
        val lessonRow = LessonTable.selectAll()
            .where { LessonTable.id eq lessonId }
            .singleOrNull() ?: throw NotFoundException("Lesson not found")

        requireLessonReader(lessonRow[LessonTable.teacherId].value, lessonId, principal)

        val items = LessonMaterialTable
            .selectAll()
            .where { LessonMaterialTable.lessonId eq lessonId }
            .map { row ->
                val materialId = row[LessonMaterialTable.materialId].value
                val material = materialService.getMaterialInternal(materialId)
                LessonMaterialResponse(
                    material = material,
                    attachedBy = row[LessonMaterialTable.attachedBy].value.toString(),
                    attachedAt = row[LessonMaterialTable.attachedAt],
                )
            }
        LessonMaterialListResponse(items)
    }

    private fun requireLessonEditor(teacherId: UUID, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        if (principal.role != UserRole.TEACHER || teacherId != principal.id)
            throw ForbiddenException("Only the owning teacher can modify lesson attachments")
    }

    private fun requireLessonReader(teacherId: UUID, lessonId: UUID, principal: UserPrincipal) {
        when (principal.role) {
            UserRole.ADMIN -> return
            UserRole.TEACHER -> if (teacherId != principal.id)
                throw ForbiddenException("No access to this lesson")
            UserRole.STUDENT -> {
                val isConfirmed = LessonStudentTable.selectAll()
                    .where {
                        (LessonStudentTable.lessonId eq lessonId) and
                                (LessonStudentTable.studentId eq principal.id) and
                                (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                    }
                    .empty().not()
                if (!isConfirmed) throw ForbiddenException("No access to this lesson")
            }
        }
    }
}

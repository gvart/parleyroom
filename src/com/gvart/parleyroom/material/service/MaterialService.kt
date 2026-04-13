package com.gvart.parleyroom.material.service

import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateMaterialInput
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class MaterialService(
    private val storage: StorageService,
) {

    fun listMaterials(
        principal: UserPrincipal,
        studentId: UUID?,
        lessonId: UUID?,
        type: MaterialType?,
    ): List<MaterialResponse> = transaction {
        val query = when (principal.role) {
            UserRole.ADMIN -> MaterialTable.selectAll()
            UserRole.TEACHER -> MaterialTable.selectAll()
                .where { MaterialTable.teacherId eq principal.id }
            UserRole.STUDENT -> {
                val confirmedLessonIds = LessonStudentTable.selectAll()
                    .where {
                        (LessonStudentTable.studentId eq principal.id) and
                                (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
                    }
                    .map { it[LessonStudentTable.lessonId].value }
                MaterialTable.selectAll().where {
                    (MaterialTable.studentId eq principal.id) or
                            (MaterialTable.lessonId inList confirmedLessonIds)
                }
            }
        }

        if (studentId != null) {
            AuthorizationHelper.requireAccessToStudent(studentId, principal)
            query.andWhere { MaterialTable.studentId eq studentId }
        }
        if (lessonId != null) {
            query.andWhere { MaterialTable.lessonId eq lessonId }
        }
        if (type != null) {
            query.andWhere { MaterialTable.type eq type }
        }

        query.map(::toResponse)
    }

    fun getMaterial(materialId: UUID, principal: UserPrincipal): MaterialResponse = transaction {
        val row = findMaterial(materialId)
        requireViewAccess(row, principal)
        toResponse(row)
    }

    fun createMaterial(input: CreateMaterialInput, principal: UserPrincipal): MaterialResponse {
        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers and admins can create materials")

        val request = input.request
        val teacherId = principal.id
        val studentUuid = request.studentId?.let(UUID::fromString)
        val lessonUuid = request.lessonId?.let(UUID::fromString)

        if (studentUuid != null) {
            transaction { AuthorizationHelper.requireAccessToStudent(studentUuid, principal) }
        }

        val materialId = UUID.randomUUID()
        val now = OffsetDateTime.now()

        return when (input) {
            is CreateMaterialInput.Link -> insertMaterial(
                materialId = materialId,
                teacherId = teacherId,
                studentUuid = studentUuid,
                lessonUuid = lessonUuid,
                request = request,
                storedUrl = request.url!!,
                contentType = null,
                fileSize = null,
                createdAt = now,
            )
            is CreateMaterialInput.File -> {
                val key = storage.buildKey(teacherId, materialId, input.fileName)
                storage.upload(key, input.contentType, input.stream, input.size)
                runCatching {
                    insertMaterial(
                        materialId = materialId,
                        teacherId = teacherId,
                        studentUuid = studentUuid,
                        lessonUuid = lessonUuid,
                        request = request,
                        storedUrl = key,
                        contentType = input.contentType,
                        fileSize = input.size,
                        createdAt = now,
                    )
                }.getOrElse { e ->
                    runCatching { storage.delete(key) }
                    throw e
                }
            }
        }
    }

    private fun insertMaterial(
        materialId: UUID,
        teacherId: UUID,
        studentUuid: UUID?,
        lessonUuid: UUID?,
        request: CreateMaterialRequest,
        storedUrl: String,
        contentType: String?,
        fileSize: Long?,
        createdAt: OffsetDateTime,
    ): MaterialResponse = transaction {
        MaterialTable.insert {
            it[id] = EntityID(materialId, MaterialTable)
            it[MaterialTable.lessonId] = lessonUuid
            it[MaterialTable.studentId] = studentUuid
            it[MaterialTable.teacherId] = teacherId
            it[name] = request.name
            it[type] = request.type
            it[url] = storedUrl
            it[MaterialTable.contentType] = contentType
            it[MaterialTable.fileSize] = fileSize
            it[MaterialTable.createdAt] = createdAt
        }
        MaterialResponse(
            id = materialId.toString(),
            teacherId = teacherId.toString(),
            studentId = studentUuid?.toString(),
            lessonId = lessonUuid?.toString(),
            name = request.name,
            type = request.type,
            contentType = contentType,
            fileSize = fileSize,
            downloadUrl = when (request.type) {
                MaterialType.LINK -> request.url
                else -> storage.presignGet(storedUrl)
            },
            createdAt = createdAt.toString(),
        )
    }

    fun updateMaterial(materialId: UUID, request: UpdateMaterialRequest, principal: UserPrincipal): MaterialResponse = transaction {
        val row = findMaterial(materialId)
        requireOwnerOrAdmin(row, principal)

        if (request.name != null) {
            if (request.name.isBlank()) throw BadRequestException("Name can't be empty")
            MaterialTable.update({ MaterialTable.id eq materialId }) {
                it[name] = request.name
            }
        }

        findMaterial(materialId).let(::toResponse)
    }

    fun deleteMaterial(materialId: UUID, principal: UserPrincipal) {
        val orphanKey = transaction {
            val row = findMaterial(materialId)
            requireOwnerOrAdmin(row, principal)
            val key = if (row[MaterialTable.type] != MaterialType.LINK) row[MaterialTable.url] else null
            MaterialTable.deleteWhere { id eq materialId }
            key
        }
        if (orphanKey != null) runCatching { storage.delete(orphanKey) }
    }

    private fun findMaterial(id: UUID): ResultRow =
        MaterialTable.selectAll()
            .where { MaterialTable.id eq id }
            .singleOrNull() ?: throw NotFoundException("Material not found")

    private fun requireViewAccess(row: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        val teacherId = row[MaterialTable.teacherId].value
        val studentId = row[MaterialTable.studentId]?.value
        val lessonId = row[MaterialTable.lessonId]?.value

        when (principal.role) {
            UserRole.TEACHER -> {
                if (teacherId == principal.id) return
                if (studentId != null) {
                    AuthorizationHelper.requireAccessToStudent(studentId, principal)
                    return
                }
            }
            UserRole.STUDENT -> {
                if (studentId == principal.id) return
                if (lessonId != null && isConfirmedLessonParticipant(lessonId, principal.id)) return
            }
            UserRole.ADMIN -> return
        }
        throw ForbiddenException("No access to this material")
    }

    private fun requireOwnerOrAdmin(row: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        if (row[MaterialTable.teacherId].value != principal.id)
            throw ForbiddenException("Only the owning teacher can perform this action")
    }

    private fun isConfirmedLessonParticipant(lessonId: UUID, studentId: UUID): Boolean =
        LessonStudentTable.selectAll()
            .where {
                (LessonStudentTable.lessonId eq lessonId) and
                        (LessonStudentTable.studentId eq studentId) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .empty().not()

    private fun toResponse(row: ResultRow): MaterialResponse {
        val type = row[MaterialTable.type]
        val rawUrl = row[MaterialTable.url]
        val downloadUrl = when {
            type == MaterialType.LINK -> rawUrl
            rawUrl.isBlank() -> null
            else -> storage.presignGet(rawUrl)
        }
        return MaterialResponse(
            id = row[MaterialTable.id].value.toString(),
            teacherId = row[MaterialTable.teacherId].value.toString(),
            studentId = row[MaterialTable.studentId]?.value?.toString(),
            lessonId = row[MaterialTable.lessonId]?.value?.toString(),
            name = row[MaterialTable.name],
            type = type,
            contentType = row[MaterialTable.contentType],
            fileSize = row[MaterialTable.fileSize],
            downloadUrl = downloadUrl,
            createdAt = row[MaterialTable.createdAt].toString(),
        )
    }
}

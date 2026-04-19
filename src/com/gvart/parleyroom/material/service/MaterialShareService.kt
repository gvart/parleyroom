package com.gvart.parleyroom.material.service

import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.material.data.FolderShareTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.transfer.ShareGrantResponse
import com.gvart.parleyroom.material.transfer.ShareListResponse
import com.gvart.parleyroom.material.transfer.ShareRequest
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class MaterialShareService(
    private val notificationService: NotificationService,
) {

    // --- Material shares -----------------------------------------------------

    fun shareMaterial(materialId: UUID, request: ShareRequest, principal: UserPrincipal): ShareListResponse {
        if (request.studentIds.isEmpty()) throw BadRequestException("studentIds must not be empty")
        val newlyGranted = mutableListOf<UUID>()

        transaction {
            val material = MaterialTable.selectAll()
                .where { MaterialTable.id eq materialId }
                .singleOrNull() ?: throw NotFoundException("Material not found")
            requireOwnerOrAdmin(material[MaterialTable.teacherId].value, principal)

            val now = OffsetDateTime.now()
            request.studentIds.map(UUID::fromString).forEach { studentId ->
                AuthorizationHelper.requireAccessToStudent(studentId, principal)
                val inserted = MaterialShareTable.insertIgnore {
                    it[MaterialShareTable.materialId] = materialId
                    it[MaterialShareTable.studentId] = studentId
                    it[sharedBy] = principal.id
                    it[sharedAt] = now
                }.insertedCount
                if (inserted > 0) newlyGranted.add(studentId)
            }
        }

        newlyGranted.forEach { studentId ->
            notificationService.createNotification(
                userId = studentId,
                actorId = principal.id,
                type = NotificationType.MATERIAL_SHARED,
                referenceId = materialId,
            )
        }

        return listMaterialShares(materialId, principal)
    }

    fun revokeMaterial(materialId: UUID, studentId: UUID, principal: UserPrincipal) {
        transaction {
            val material = MaterialTable.selectAll()
                .where { MaterialTable.id eq materialId }
                .singleOrNull() ?: throw NotFoundException("Material not found")
            requireOwnerOrAdmin(material[MaterialTable.teacherId].value, principal)

            MaterialShareTable.deleteWhere {
                (MaterialShareTable.materialId eq materialId) and
                        (MaterialShareTable.studentId eq studentId)
            }
        }
    }

    fun listMaterialShares(materialId: UUID, principal: UserPrincipal): ShareListResponse = transaction {
        val material = MaterialTable.selectAll()
            .where { MaterialTable.id eq materialId }
            .singleOrNull() ?: throw NotFoundException("Material not found")
        requireOwnerOrAdmin(material[MaterialTable.teacherId].value, principal)

        val grants = MaterialShareTable
            .join(UserTable, JoinType.INNER, MaterialShareTable.studentId, UserTable.id)
            .selectAll()
            .where { MaterialShareTable.materialId eq materialId }
            .map {
                ShareGrantResponse(
                    studentId = it[MaterialShareTable.studentId].value.toString(),
                    firstName = it[UserTable.firstName],
                    lastName = it[UserTable.lastName],
                    sharedBy = it[MaterialShareTable.sharedBy].value.toString(),
                    sharedAt = it[MaterialShareTable.sharedAt],
                )
            }
        ShareListResponse(grants)
    }

    // --- Folder shares -------------------------------------------------------

    fun shareFolder(folderId: UUID, request: ShareRequest, principal: UserPrincipal): ShareListResponse {
        if (request.studentIds.isEmpty()) throw BadRequestException("studentIds must not be empty")
        val newlyGranted = mutableListOf<UUID>()

        transaction {
            val folder = MaterialFolderTable.selectAll()
                .where { MaterialFolderTable.id eq folderId }
                .singleOrNull() ?: throw NotFoundException("Folder not found")
            requireOwnerOrAdmin(folder[MaterialFolderTable.teacherId].value, principal)

            val now = OffsetDateTime.now()
            request.studentIds.map(UUID::fromString).forEach { studentId ->
                AuthorizationHelper.requireAccessToStudent(studentId, principal)
                val inserted = FolderShareTable.insertIgnore {
                    it[FolderShareTable.folderId] = folderId
                    it[FolderShareTable.studentId] = studentId
                    it[sharedBy] = principal.id
                    it[sharedAt] = now
                }.insertedCount
                if (inserted > 0) newlyGranted.add(studentId)
            }
        }

        newlyGranted.forEach { studentId ->
            notificationService.createNotification(
                userId = studentId,
                actorId = principal.id,
                type = NotificationType.FOLDER_SHARED,
                referenceId = folderId,
            )
        }

        return listFolderShares(folderId, principal)
    }

    fun revokeFolder(folderId: UUID, studentId: UUID, principal: UserPrincipal) {
        transaction {
            val folder = MaterialFolderTable.selectAll()
                .where { MaterialFolderTable.id eq folderId }
                .singleOrNull() ?: throw NotFoundException("Folder not found")
            requireOwnerOrAdmin(folder[MaterialFolderTable.teacherId].value, principal)

            FolderShareTable.deleteWhere {
                (FolderShareTable.folderId eq folderId) and
                        (FolderShareTable.studentId eq studentId)
            }
        }
    }

    fun listFolderShares(folderId: UUID, principal: UserPrincipal): ShareListResponse = transaction {
        val folder = MaterialFolderTable.selectAll()
            .where { MaterialFolderTable.id eq folderId }
            .singleOrNull() ?: throw NotFoundException("Folder not found")
        requireOwnerOrAdmin(folder[MaterialFolderTable.teacherId].value, principal)

        val grants = FolderShareTable
            .join(UserTable, JoinType.INNER, FolderShareTable.studentId, UserTable.id)
            .selectAll()
            .where { FolderShareTable.folderId eq folderId }
            .map {
                ShareGrantResponse(
                    studentId = it[FolderShareTable.studentId].value.toString(),
                    firstName = it[UserTable.firstName],
                    lastName = it[UserTable.lastName],
                    sharedBy = it[FolderShareTable.sharedBy].value.toString(),
                    sharedAt = it[FolderShareTable.sharedAt],
                )
            }
        ShareListResponse(grants)
    }

    private fun requireOwnerOrAdmin(ownerId: UUID, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        if (principal.role != UserRole.TEACHER || ownerId != principal.id)
            throw ForbiddenException("Only the owning teacher or an admin can manage shares")
    }
}

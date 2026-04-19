package com.gvart.parleyroom.material.service

import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.material.data.FolderShareTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.CreateFolderRequest
import com.gvart.parleyroom.material.transfer.FolderTreeNode
import com.gvart.parleyroom.material.transfer.MaterialFolderResponse
import com.gvart.parleyroom.material.transfer.UpdateFolderRequest
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class MaterialFolderService(
    private val accessResolver: MaterialAccessResolver,
    private val storage: StorageService,
) {

    fun createFolder(request: CreateFolderRequest, principal: UserPrincipal): MaterialFolderResponse = transaction {
        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers and admins can create folders")

        val parentUuid = request.parentFolderId?.let(UUID::fromString)
        if (parentUuid != null) {
            val parent = findFolderRow(parentUuid)
            if (principal.role == UserRole.TEACHER && parent[MaterialFolderTable.teacherId].value != principal.id)
                throw ForbiddenException("Cannot nest folders under another teacher's folder")
        }

        val teacherId = when (principal.role) {
            UserRole.TEACHER -> principal.id
            UserRole.ADMIN -> parentUuid?.let { findFolderRow(it)[MaterialFolderTable.teacherId].value } ?: principal.id
            else -> throw ForbiddenException("Only teachers and admins can create folders")
        }

        ensureNameUnique(teacherId, parentUuid, request.name, excludeId = null)

        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        MaterialFolderTable.insert {
            it[MaterialFolderTable.id] = EntityID(id, MaterialFolderTable)
            it[parentFolderId] = parentUuid?.let { p -> EntityID(p, MaterialFolderTable) }
            it[MaterialFolderTable.teacherId] = teacherId
            it[name] = request.name.trim()
            it[createdAt] = now
            it[updatedAt] = now
        }
        toResponse(findFolderRow(id))
    }

    fun updateFolder(folderId: UUID, request: UpdateFolderRequest, principal: UserPrincipal): MaterialFolderResponse =
        transaction {
            val row = findFolderRow(folderId)
            accessResolver.requireFolderOwnership(row, principal)

            val currentParent = row[MaterialFolderTable.parentFolderId]?.value
            val teacherId = row[MaterialFolderTable.teacherId].value

            val newParent: UUID? = when {
                request.moveToRoot -> null
                request.parentFolderId != null -> UUID.fromString(request.parentFolderId)
                else -> currentParent
            }

            if (newParent != null) {
                val parentRow = findFolderRow(newParent)
                if (parentRow[MaterialFolderTable.teacherId].value != teacherId)
                    throw ForbiddenException("Cannot move folder under another teacher's folder")
                // Cycle check: newParent must not be a descendant of the folder being moved.
                if (newParent == folderId || isDescendant(newParent, folderId))
                    throw BadRequestException("Cannot move a folder under itself or one of its descendants")
            }

            val newName = request.name?.trim()?.takeIf { it.isNotBlank() } ?: row[MaterialFolderTable.name]
            if (newName != row[MaterialFolderTable.name] || newParent != currentParent) {
                ensureNameUnique(teacherId, newParent, newName, excludeId = folderId)
            }

            MaterialFolderTable.update({ MaterialFolderTable.id eq folderId }) {
                it[name] = newName
                it[parentFolderId] = newParent?.let { p -> EntityID(p, MaterialFolderTable) }
                it[updatedAt] = OffsetDateTime.now()
            }
            toResponse(findFolderRow(folderId))
        }

    fun deleteFolder(folderId: UUID, cascade: Boolean, principal: UserPrincipal) {
        // Collect MinIO keys to purge after the DB commit. Cascading the DB
        // alone would orphan the stored objects — no later process finds them
        // because the row pointing at the key is gone.
        val keysToPurge = transaction {
            val row = findFolderRow(folderId)
            accessResolver.requireFolderOwnership(row, principal)

            val hasChildren = MaterialFolderTable.selectAll()
                .where { MaterialFolderTable.parentFolderId eq folderId }
                .empty().not()

            val hasMaterials = MaterialTable.selectAll()
                .where { MaterialTable.folderId eq folderId }
                .empty().not()

            if ((hasChildren || hasMaterials) && !cascade) {
                throw ConflictException("Folder is not empty. Pass cascade=true to delete contents.")
            }

            if (cascade) deleteCascade(folderId)
            else {
                MaterialFolderTable.deleteWhere { MaterialFolderTable.id eq folderId }
                emptyList()
            }
        }
        keysToPurge.forEach { key -> runCatching { storage.delete(key) } }
    }

    fun listFolders(principal: UserPrincipal, tree: Boolean): List<FolderTreeNode> = transaction {
        val rows = when (principal.role) {
            UserRole.ADMIN -> MaterialFolderTable.selectAll().toList()
            UserRole.TEACHER -> MaterialFolderTable.selectAll()
                .where { MaterialFolderTable.teacherId eq principal.id }
                .toList()
            UserRole.STUDENT -> {
                val visibleIds = accessResolver.accessibleFolderIdsForStudent(principal.id)
                if (visibleIds.isEmpty()) emptyList()
                else MaterialFolderTable.selectAll()
                    .where { MaterialFolderTable.id inList visibleIds }
                    .toList()
            }
        }

        val nodes = rows.map { toResponse(it) }
        if (!tree) return@transaction nodes.map { FolderTreeNode(it, emptyList()) }

        val byParent = nodes.groupBy { it.parentFolderId }
        val visibleIds = nodes.map { it.id }.toSet()

        fun build(parentKey: String?): List<FolderTreeNode> =
            (byParent[parentKey] ?: emptyList()).map { folder ->
                FolderTreeNode(folder, build(folder.id))
            }

        // Roots for students = folders whose parent is either null OR not in the visible set
        // (i.e. the shared folders become virtual roots even if their real parent isn't shared).
        when (principal.role) {
            UserRole.STUDENT -> nodes
                .filter { it.parentFolderId == null || it.parentFolderId !in visibleIds }
                .map { root -> FolderTreeNode(root, build(root.id)) }
            else -> build(null)
        }
    }

    fun getFolder(folderId: UUID, principal: UserPrincipal): MaterialFolderResponse = transaction {
        val row = findFolderRow(folderId)
        when (principal.role) {
            UserRole.ADMIN -> Unit
            UserRole.TEACHER -> accessResolver.requireFolderOwnership(row, principal)
            UserRole.STUDENT -> {
                val visible = accessResolver.accessibleFolderIdsForStudent(principal.id)
                if (folderId !in visible) throw ForbiddenException("No access to this folder")
            }
        }
        toResponse(row)
    }

    /**
     * Depth-first delete that collects every MinIO key for non-LINK materials
     * so the caller can purge them from storage after the transaction commits.
     * Shares and lesson attachments are removed automatically via ON DELETE CASCADE.
     */
    private fun deleteCascade(folderId: UUID): List<String> {
        val keys = mutableListOf<String>()

        val children = MaterialFolderTable
            .select(MaterialFolderTable.id)
            .where { MaterialFolderTable.parentFolderId eq folderId }
            .map { it[MaterialFolderTable.id].value }
        children.forEach { keys.addAll(deleteCascade(it)) }

        MaterialTable
            .selectAll()
            .where { MaterialTable.folderId eq folderId }
            .forEach { row ->
                if (row[MaterialTable.type] != MaterialType.LINK) {
                    val url = row[MaterialTable.url]
                    if (url.isNotBlank()) keys.add(url)
                }
            }

        MaterialTable.deleteWhere { MaterialTable.folderId eq folderId }
        MaterialFolderTable.deleteWhere { MaterialFolderTable.id eq folderId }
        return keys
    }

    private fun isDescendant(candidateId: UUID, ancestorId: UUID): Boolean {
        var cur: UUID? = candidateId
        // Walk up from candidate to root; if we hit ancestor, candidate is a descendant.
        val guard = HashSet<UUID>()
        while (cur != null) {
            if (cur == ancestorId) return true
            if (!guard.add(cur)) return false // safety against accidental cycles
            cur = MaterialFolderTable
                .select(MaterialFolderTable.parentFolderId)
                .where { MaterialFolderTable.id eq cur!! }
                .firstOrNull()
                ?.get(MaterialFolderTable.parentFolderId)
                ?.value
        }
        return false
    }

    private fun ensureNameUnique(teacherId: UUID, parentId: UUID?, name: String, excludeId: UUID?) {
        val query = MaterialFolderTable.selectAll()
            .where {
                val base = (MaterialFolderTable.teacherId eq teacherId) and
                        (MaterialFolderTable.name.lowerCase() eq name.lowercase())
                if (parentId != null) base and (MaterialFolderTable.parentFolderId eq parentId)
                else base and MaterialFolderTable.parentFolderId.isNull()
            }
        if (excludeId != null) query.andWhere { MaterialFolderTable.id neq excludeId }
        if (!query.empty()) throw ConflictException("A folder named \"$name\" already exists here")
    }

    private fun findFolderRow(id: UUID): ResultRow =
        MaterialFolderTable.selectAll()
            .where { MaterialFolderTable.id eq id }
            .singleOrNull() ?: throw NotFoundException("Folder not found")

    internal fun toResponse(row: ResultRow): MaterialFolderResponse {
        val folderId = row[MaterialFolderTable.id].value
        val materialCount = MaterialTable.selectAll()
            .where { MaterialTable.folderId eq folderId }
            .count()
            .toInt()
        val childCount = MaterialFolderTable.selectAll()
            .where { MaterialFolderTable.parentFolderId eq folderId }
            .count()
            .toInt()
        val shareCount = FolderShareTable.selectAll()
            .where { FolderShareTable.folderId eq folderId }
            .count()
            .toInt()
        return MaterialFolderResponse(
            id = folderId.toString(),
            teacherId = row[MaterialFolderTable.teacherId].value.toString(),
            parentFolderId = row[MaterialFolderTable.parentFolderId]?.value?.toString(),
            name = row[MaterialFolderTable.name],
            materialCount = materialCount,
            childFolderCount = childCount,
            sharedWithCount = shareCount,
            createdAt = row[MaterialFolderTable.createdAt],
            updatedAt = row[MaterialFolderTable.updatedAt],
        )
    }
}

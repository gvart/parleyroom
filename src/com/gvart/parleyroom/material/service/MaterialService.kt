package com.gvart.parleyroom.material.service

import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.service.singleOrNotFound
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.material.data.LessonMaterialTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialGrammarTopicTable
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.data.MaterialSkill
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialTopicTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.BulkMaterialAction
import com.gvart.parleyroom.material.transfer.BulkMaterialRequest
import com.gvart.parleyroom.material.transfer.BulkMaterialResponse
import com.gvart.parleyroom.material.transfer.CreateMaterialInput
import com.gvart.parleyroom.material.transfer.CreateMaterialRequest
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.material.transfer.UpdateMaterialRequest
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.topic.service.LibraryAccess
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

class MaterialService(
    private val storage: StorageService,
    private val accessResolver: MaterialAccessResolver,
    private val notificationService: NotificationService,
) {

    fun listMaterials(
        principal: UserPrincipal,
        folderId: UUID?,
        unfiled: Boolean,
        lessonId: UUID?,
        type: MaterialType?,
        level: LanguageLevel?,
        skill: MaterialSkill?,
        page: PageRequest,
        topicId: UUID? = null,
        grammarTopicId: UUID? = null,
    ): MaterialPageResponse = transaction {
        val query: Query = when (principal.role) {
            UserRole.ADMIN -> MaterialTable.selectAll()
            UserRole.TEACHER -> MaterialTable.selectAll()
                .where { MaterialTable.teacherId eq principal.id }
            UserRole.STUDENT -> {
                val visibleIds = accessResolver.accessibleMaterialIdsForStudent(principal.id)
                if (visibleIds.isEmpty()) MaterialTable.selectAll().where { Op.FALSE }
                else MaterialTable.selectAll().where { MaterialTable.id inList visibleIds }
            }
        }

        if (folderId != null) query.andWhere { MaterialTable.folderId eq folderId }
        else if (unfiled) query.andWhere { MaterialTable.folderId.isNull() }

        if (lessonId != null) {
            val attachedIds = LessonMaterialTable
                .select(LessonMaterialTable.materialId)
                .where { LessonMaterialTable.lessonId eq lessonId }
                .map { it[LessonMaterialTable.materialId].value }
            query.andWhere { MaterialTable.id inList attachedIds }
        }
        if (type != null) query.andWhere { MaterialTable.type eq type }
        if (level != null) query.andWhere { MaterialTable.level eq level }
        if (skill != null) query.andWhere { MaterialTable.skill eq skill }
        if (topicId != null) query.andWhere {
            MaterialTable.id inSubQuery MaterialTopicTable.select(MaterialTopicTable.materialId)
                .where { MaterialTopicTable.topicId eq topicId }
        }
        if (grammarTopicId != null) query.andWhere {
            MaterialTable.id inSubQuery MaterialGrammarTopicTable.select(MaterialGrammarTopicTable.materialId)
                .where { MaterialGrammarTopicTable.grammarTopicId eq grammarTopicId }
        }

        val total = query.count()
        val items = toResponses(
            query
                .limit(page.pageSize)
                .offset(page.offset)
                .toList()
        )

        MaterialPageResponse(
            materials = items,
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun getMaterial(materialId: UUID, principal: UserPrincipal): MaterialResponse = transaction {
        val row = findMaterial(materialId)
        requireViewAccess(row, principal)
        toResponse(row)
    }

    /** Called by other services that have already authorised the read. */
    fun getMaterialInternal(materialId: UUID): MaterialResponse = transaction {
        toResponse(findMaterial(materialId))
    }

    fun getDownloadTarget(materialId: UUID, principal: UserPrincipal): MaterialDownloadTarget = transaction {
        val row = findMaterial(materialId)
        requireViewAccess(row, principal)
        val type = row[MaterialTable.type]
        if (type == MaterialType.LINK) throw BadRequestException("LINK materials have no downloadable file")
        val key = row[MaterialTable.url]
        if (key.isBlank()) throw NotFoundException("Material has no stored file", code = "MATERIAL_FILE_NOT_FOUND")
        MaterialDownloadTarget(
            storageKey = key,
            fileName = key.substringAfterLast('/'),
            contentType = row[MaterialTable.contentType],
        )
    }

    data class MaterialDownloadTarget(
        val storageKey: String,
        val fileName: String,
        val contentType: String?,
    )

    fun createMaterial(input: CreateMaterialInput, principal: UserPrincipal): MaterialResponse {
        if (principal.role == UserRole.STUDENT)
            throw ForbiddenException("Only teachers and admins can create materials")

        val request = input.request
        val folderUuid = request.folderId?.let(UUID::fromString)

        // When dropping into someone else's folder (admin operating on behalf of
        // a teacher), the material's owner must match the folder's owner so every
        // downstream check — share, delete, attach — keeps working.
        val teacherId: UUID = if (folderUuid != null) {
            transaction {
                val folder = MaterialFolderTable.selectAll()
                    .where { MaterialFolderTable.id eq folderUuid }
                    .singleOrNotFound("Folder")
                val folderOwner = folder[MaterialFolderTable.teacherId].value
                if (principal.role == UserRole.TEACHER && folderOwner != principal.id)
                    throw ForbiddenException("Cannot place material in another teacher's folder")
                folderOwner
            }
        } else {
            if (principal.role == UserRole.ADMIN)
                throw BadRequestException("Admin uploads require a folderId so ownership can be resolved")
            principal.id
        }

        // Validate tags before any bytes hit storage.
        val (topicIds, grammarIds) = transaction {
            LibraryAccess.requireTopics(teacherId, request.topicIds) to
                    LibraryAccess.requireGrammarTopics(teacherId, request.grammarTopicIds)
        }
        val materialId = UUID.randomUUID()

        return when (input) {
            is CreateMaterialInput.Link -> insertMaterial(
                materialId = materialId,
                teacherId = teacherId,
                folderUuid = folderUuid,
                request = request,
                storedUrl = request.url!!,
                contentType = null,
                fileSize = null,
                topicIds = topicIds,
                grammarIds = grammarIds,
            )
            is CreateMaterialInput.File -> {
                val key = storage.buildKey(teacherId, materialId, input.fileName)
                storage.upload(key, input.contentType, input.stream, input.size)
                runCatching {
                    insertMaterial(
                        materialId = materialId,
                        teacherId = teacherId,
                        folderUuid = folderUuid,
                        request = request,
                        storedUrl = key,
                        contentType = input.contentType,
                        fileSize = input.size,
                        topicIds = topicIds,
                        grammarIds = grammarIds,
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
        folderUuid: UUID?,
        request: CreateMaterialRequest,
        storedUrl: String,
        contentType: String?,
        fileSize: Long?,
        topicIds: List<UUID>,
        grammarIds: List<UUID>,
    ): MaterialResponse = transaction {
        MaterialTable.insert {
            it[id] = EntityID(materialId, MaterialTable)
            it[MaterialTable.teacherId] = teacherId
            it[MaterialTable.folderId] = folderUuid?.let { f -> EntityID(f, MaterialFolderTable) }
            it[name] = request.name
            it[type] = request.type
            it[url] = storedUrl
            it[MaterialTable.contentType] = contentType
            it[MaterialTable.fileSize] = fileSize
            it[level] = request.level
            it[skill] = request.skill
            it[createdAt] = OffsetDateTime.now()
        }
        replaceTags(materialId, topicIds, grammarIds)
        findMaterial(materialId).let(::toResponse)
    }

    fun updateMaterial(materialId: UUID, request: UpdateMaterialRequest, principal: UserPrincipal): MaterialResponse =
        transaction {
            val row = findMaterial(materialId)
            requireOwnerOrAdmin(row, principal)

            val hasAny = request.name != null || request.folderId != null ||
                    request.level != null || request.skill != null ||
                    request.topicIds != null || request.grammarTopicIds != null
            if (!hasAny) return@transaction toResponse(row)

            val ownerId = row[MaterialTable.teacherId].value
            val topicIds = request.topicIds?.let { LibraryAccess.requireTopics(ownerId, it) }
            val grammarIds = request.grammarTopicIds?.let { LibraryAccess.requireGrammarTopics(ownerId, it) }

            if (request.folderId != null) {
                val newFolder = UUID.fromString(request.folderId)
                val folderRow = MaterialFolderTable.selectAll()
                    .where { MaterialFolderTable.id eq newFolder }
                    .singleOrNotFound("Folder")
                if (folderRow[MaterialFolderTable.teacherId].value != row[MaterialTable.teacherId].value)
                    throw ForbiddenException("Cannot move material to another teacher's folder")
            }

            val hasColumns = request.name != null || request.folderId != null ||
                    request.level != null || request.skill != null
            if (hasColumns) MaterialTable.update({ MaterialTable.id eq materialId }) {
                if (request.name != null) {
                    if (request.name.isBlank()) throw BadRequestException("Name can't be empty")
                    it[name] = request.name
                }
                if (request.folderId != null) {
                    it[folderId] = EntityID(UUID.fromString(request.folderId), MaterialFolderTable)
                }
                if (request.level != null) it[level] = request.level
                if (request.skill != null) it[skill] = request.skill
            }
            replaceTags(materialId, topicIds, grammarIds)
            toResponse(findMaterial(materialId))
        }

    /** Replaces the tag links that are non-null; null leaves that tag kind untouched. */
    private fun replaceTags(materialId: UUID, topicIds: List<UUID>?, grammarIds: List<UUID>?) {
        topicIds?.let { ids ->
            MaterialTopicTable.deleteWhere { MaterialTopicTable.materialId eq materialId }
            MaterialTopicTable.batchInsert(ids) {
                this[MaterialTopicTable.materialId] = materialId
                this[MaterialTopicTable.topicId] = it
            }
        }
        grammarIds?.let { ids ->
            MaterialGrammarTopicTable.deleteWhere { MaterialGrammarTopicTable.materialId eq materialId }
            MaterialGrammarTopicTable.batchInsert(ids) {
                this[MaterialGrammarTopicTable.materialId] = materialId
                this[MaterialGrammarTopicTable.grammarTopicId] = it
            }
        }
    }

    fun clearMaterialFolder(materialId: UUID, principal: UserPrincipal): MaterialResponse = transaction {
        val row = findMaterial(materialId)
        requireOwnerOrAdmin(row, principal)
        MaterialTable.update({ MaterialTable.id eq materialId }) { it[folderId] = null }
        toResponse(findMaterial(materialId))
    }

    fun clearMaterialLevel(materialId: UUID, principal: UserPrincipal): MaterialResponse = transaction {
        val row = findMaterial(materialId)
        requireOwnerOrAdmin(row, principal)
        MaterialTable.update({ MaterialTable.id eq materialId }) { it[level] = null }
        toResponse(findMaterial(materialId))
    }

    fun clearMaterialSkill(materialId: UUID, principal: UserPrincipal): MaterialResponse = transaction {
        val row = findMaterial(materialId)
        requireOwnerOrAdmin(row, principal)
        MaterialTable.update({ MaterialTable.id eq materialId }) { it[skill] = null }
        toResponse(findMaterial(materialId))
    }

    fun deleteMaterial(materialId: UUID, principal: UserPrincipal) {
        val orphanKey = transaction {
            val row = findMaterial(materialId)
            requireOwnerOrAdmin(row, principal)
            val key = if (row[MaterialTable.type] != MaterialType.LINK) row[MaterialTable.url] else null
            MaterialTable.deleteWhere { MaterialTable.id eq materialId }
            key
        }
        if (orphanKey != null) runCatching { storage.delete(orphanKey) }
    }

    fun bulk(request: BulkMaterialRequest, principal: UserPrincipal): BulkMaterialResponse {
        val ids = request.materialIds.map(UUID::fromString)
        return when (request.action) {
            BulkMaterialAction.MOVE -> {
                val target = request.targetFolderId?.let(UUID::fromString)
                var affected = 0
                transaction {
                    if (target != null) {
                        val folderRow = MaterialFolderTable.selectAll()
                            .where { MaterialFolderTable.id eq target }
                            .singleOrNotFound("Target folder")
                        if (principal.role == UserRole.TEACHER &&
                            folderRow[MaterialFolderTable.teacherId].value != principal.id
                        ) throw ForbiddenException("Target folder is not yours")
                    }
                    val rows = MaterialTable.selectAll()
                        .where { MaterialTable.id inList ids }
                        .toList()
                    if (rows.size != ids.size) throw NotFoundException("One or more materials not found", code = "MATERIAL_NOT_FOUND")
                    rows.forEach { requireOwnerOrAdmin(it, principal) }

                    affected = MaterialTable.update({ MaterialTable.id inList ids }) {
                        if (target != null) it[folderId] = EntityID(target, MaterialFolderTable)
                        else it[folderId] = null
                    }
                }
                BulkMaterialResponse(request.action, affected)
            }
            BulkMaterialAction.SHARE -> {
                val studentIds = request.studentIds!!.map(UUID::fromString)
                var affected = 0
                val notifications = mutableListOf<Pair<UUID, UUID>>() // (studentId, materialId)
                transaction {
                    val rows = MaterialTable.selectAll()
                        .where { MaterialTable.id inList ids }
                        .toList()
                    if (rows.size != ids.size) throw NotFoundException("One or more materials not found", code = "MATERIAL_NOT_FOUND")
                    rows.forEach { requireOwnerOrAdmin(it, principal) }

                    val now = OffsetDateTime.now()
                    studentIds.forEach { studentId ->
                        AuthorizationHelper.requireAccessToStudent(studentId, principal)
                        ids.forEach { materialId ->
                            val inserted = MaterialShareTable.insertIgnore {
                                it[MaterialShareTable.materialId] = materialId
                                it[MaterialShareTable.studentId] = studentId
                                it[sharedBy] = principal.id
                                it[sharedAt] = now
                            }.insertedCount
                            if (inserted > 0) {
                                affected++
                                notifications.add(studentId to materialId)
                            }
                        }
                    }
                }
                notifications.forEach { (studentId, materialId) ->
                    notificationService.createNotification(
                        userId = studentId,
                        actorId = principal.id,
                        type = NotificationType.MATERIAL_SHARED,
                        referenceId = materialId,
                    )
                }
                BulkMaterialResponse(request.action, affected)
            }
            BulkMaterialAction.DELETE -> {
                val (keysToPurge, deleted) = transaction {
                    val rows = MaterialTable.selectAll()
                        .where { MaterialTable.id inList ids }
                        .toList()
                    if (rows.size != ids.size) throw NotFoundException("One or more materials not found", code = "MATERIAL_NOT_FOUND")
                    rows.forEach { requireOwnerOrAdmin(it, principal) }
                    val keys = rows
                        .filter { it[MaterialTable.type] != MaterialType.LINK }
                        .map { it[MaterialTable.url] }
                    val count = MaterialTable.deleteWhere { MaterialTable.id inList ids }
                    keys to count
                }
                keysToPurge.forEach { key -> runCatching { storage.delete(key) } }
                BulkMaterialResponse(request.action, deleted)
            }
        }
    }

    private fun findMaterial(id: UUID): ResultRow =
        MaterialTable.findByIdOrThrow(id, "Material")

    private fun requireViewAccess(row: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        val teacherId = row[MaterialTable.teacherId].value
        val materialId = row[MaterialTable.id].value
        when (principal.role) {
            UserRole.TEACHER -> if (teacherId == principal.id) return
            UserRole.STUDENT -> if (accessResolver.canStudentView(materialId, principal.id)) return
            UserRole.ADMIN -> return // unreachable — handled by the early return
        }
        throw ForbiddenException("No access to this material")
    }

    private fun requireOwnerOrAdmin(row: ResultRow, principal: UserPrincipal) =
        AuthorizationHelper.requireOwnerOrAdmin(
            row[MaterialTable.teacherId].value, principal,
            "Only the owning teacher can perform this action",
        )

    private fun toResponse(row: ResultRow): MaterialResponse = toResponses(listOf(row)).single()

    private fun toResponses(rows: List<ResultRow>): List<MaterialResponse> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[MaterialTable.id].value }
        val topicsByMaterial = MaterialTopicTable.selectAll()
            .where { MaterialTopicTable.materialId inList ids }
            .groupBy({ it[MaterialTopicTable.materialId].value }) { it[MaterialTopicTable.topicId].value.toString() }
        val grammarByMaterial = MaterialGrammarTopicTable.selectAll()
            .where { MaterialGrammarTopicTable.materialId inList ids }
            .groupBy({ it[MaterialGrammarTopicTable.materialId].value }) { it[MaterialGrammarTopicTable.grammarTopicId].value.toString() }
        return rows.map { row ->
            toResponse(row, topicsByMaterial[row[MaterialTable.id].value].orEmpty(), grammarByMaterial[row[MaterialTable.id].value].orEmpty())
        }
    }

    private fun toResponse(row: ResultRow, topicIds: List<String>, grammarTopicIds: List<String>): MaterialResponse {
        val type = row[MaterialTable.type]
        val rawUrl = row[MaterialTable.url]
        val materialId = row[MaterialTable.id].value
        val downloadUrl = when {
            type == MaterialType.LINK -> rawUrl
            rawUrl.isBlank() -> null
            else -> "/api/v1/materials/$materialId/file"
        }
        return MaterialResponse(
            id = materialId.toString(),
            teacherId = row[MaterialTable.teacherId].value.toString(),
            folderId = row[MaterialTable.folderId]?.value?.toString(),
            name = row[MaterialTable.name],
            type = type,
            level = row[MaterialTable.level],
            skill = row[MaterialTable.skill],
            contentType = row[MaterialTable.contentType],
            fileSize = row[MaterialTable.fileSize],
            downloadUrl = downloadUrl,
            topicIds = topicIds,
            grammarTopicIds = grammarTopicIds,
            createdAt = row[MaterialTable.createdAt],
        )
    }
}

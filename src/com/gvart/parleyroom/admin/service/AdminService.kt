package com.gvart.parleyroom.admin.service

import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.service.singleOrNotFound
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.admin.transfer.AdminCreateUserRequest
import com.gvart.parleyroom.admin.transfer.AdminUpdateUserRequest
import com.gvart.parleyroom.admin.transfer.AdminUserListResponse
import com.gvart.parleyroom.admin.transfer.AdminUserResponse
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.document.data.DocumentLessonTable
import com.gvart.parleyroom.document.data.DocumentStudentTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.data.HomeworkUploadTable
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.FolderShareTable
import com.gvart.parleyroom.material.data.LessonMaterialTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.data.DEFAULT_NATIVE_LANGUAGE
import com.gvart.parleyroom.user.data.requireSupportedLocale
import com.gvart.parleyroom.user.data.requireSupportedNativeLanguage
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.LessonVocabTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.notInSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.json.contains
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

class AdminService(private val storage: StorageService) {
    private val log = LoggerFactory.getLogger(AdminService::class.java)

    fun listUsers(
        page: PageRequest,
        role: UserRole?,
        status: UserStatus?,
        search: String?,
    ): AdminUserListResponse = transaction {
        val filters: List<Op<Boolean>> = buildList {
            if (role != null) add(UserTable.role eq role)
            if (status != null) add(UserTable.status eq status)
            if (!search.isNullOrBlank()) {
                val pattern = "%${search.trim().lowercase()}%"
                add(
                    (UserTable.email.lowerCase() like pattern) or
                        (UserTable.firstName.lowerCase() like pattern) or
                        (UserTable.lastName.lowerCase() like pattern) or
                        (UserTable.initials.lowerCase() like pattern)
                )
            }
        }

        val query = if (filters.isEmpty()) UserTable.selectAll()
        else UserTable.selectAll().where { filters.reduce { a, b -> a and b } }

        val total = query.count()
        val rows = query
            .orderBy(UserTable.createdAt, SortOrder.DESC)
            .limit(page.pageSize)
            .offset(page.offset)
            .map { toResponse(it) }

        AdminUserListResponse(
            users = rows,
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun getUser(id: UUID): AdminUserResponse = transaction {
        val row = UserTable.selectAll()
            .where { UserTable.id eq id }
            .singleOrNotFound("User")
        toResponse(row)
    }

    fun createUser(request: AdminCreateUserRequest): AdminUserResponse = transaction {
        request.locale?.let(::requireSupportedLocale)
        request.nativeLanguage?.let(::requireSupportedNativeLanguage)
        val email = request.email.trim()
        val firstName = request.firstName.trim()
        val lastName = request.lastName.trim()

        val exists = UserTable.selectAll()
            .where { UserTable.email eq email }
            .empty().not()
        if (exists) throw ConflictException("User with this email already exists", code = "EMAIL_ALREADY_EXISTS")

        val newId = UserTable.insert {
            it[UserTable.email] = email
            it[UserTable.firstName] = firstName
            it[UserTable.lastName] = lastName
            it[passwordHash] = BCrypt.hashpw(request.password, BCrypt.gensalt())
            it[UserTable.role] = request.role
            it[initials] = "${firstName[0]}${lastName[0]}"
            if (request.level != null) it[level] = request.level
            if (request.locale != null) it[locale] = request.locale
            val native = request.nativeLanguage ?: DEFAULT_NATIVE_LANGUAGE.takeIf { request.role == UserRole.STUDENT }
            if (native != null) it[nativeLanguage] = native
            it[UserTable.status] = UserStatus.ACTIVE
        }[UserTable.id].value

        toResponse(loadById(newId))
    }

    fun updateUser(
        id: UUID,
        principal: UserPrincipal,
        request: AdminUpdateUserRequest,
    ): AdminUserResponse = transaction {
        request.locale?.let(::requireSupportedLocale)
        request.nativeLanguage?.let(::requireSupportedNativeLanguage)
        val current = loadById(id)
        val currentRole = current[UserTable.role]
        val currentStatus = current[UserTable.status]

        if (id == principal.id) {
            if (request.role != null && request.role != currentRole)
                throw BadRequestException("Admins cannot change their own role", code = "ADMIN_SELF_ACTION")
            if (request.status != null && request.status != UserStatus.ACTIVE)
                throw BadRequestException("Admins cannot deactivate themselves", code = "ADMIN_SELF_ACTION")
        }

        val newEmail = request.email?.trim()
        if (newEmail != null && newEmail != current[UserTable.email]) {
            val emailTaken = UserTable.selectAll()
                .where { (UserTable.email eq newEmail) and (UserTable.id neq id) }
                .empty().not()
            if (emailTaken) throw ConflictException("User with this email already exists", code = "EMAIL_ALREADY_EXISTS")
        }

        val newFirstName = request.firstName?.trim() ?: current[UserTable.firstName]
        val newLastName = request.lastName?.trim() ?: current[UserTable.lastName]
        val nameChanged = request.firstName != null || request.lastName != null

        UserTable.update({ UserTable.id eq id }) {
            if (newEmail != null) it[email] = newEmail
            if (request.firstName != null) it[firstName] = newFirstName
            if (request.lastName != null) it[lastName] = newLastName
            if (nameChanged) it[initials] = "${newFirstName[0]}${newLastName[0]}"
            if (request.role != null) it[role] = request.role
            if (request.status != null) it[status] = request.status
            if (request.level != null) it[level] = request.level
            if (request.locale != null) it[locale] = request.locale
            if (request.nativeLanguage != null) it[nativeLanguage] = request.nativeLanguage
            else if (request.role == UserRole.STUDENT && current[UserTable.nativeLanguage] == null) it[nativeLanguage] = DEFAULT_NATIVE_LANGUAGE
            it[updatedAt] = OffsetDateTime.now()
        }

        if (request.status == UserStatus.INACTIVE && currentStatus != UserStatus.INACTIVE) {
            RefreshTokenTable.deleteWhere { userId eq id }
        }

        toResponse(loadById(id))
    }

    fun softDeleteUser(id: UUID, principal: UserPrincipal): Unit = transaction {
        if (id == principal.id) throw BadRequestException("Admins cannot delete themselves", code = "ADMIN_SELF_ACTION")
        val current = loadById(id)
        UserTable.update({ UserTable.id eq id }) {
            it[status] = UserStatus.INACTIVE
            it[updatedAt] = OffsetDateTime.now()
        }
        if (current[UserTable.status] != UserStatus.INACTIVE) {
            RefreshTokenTable.deleteWhere { userId eq id }
        }
    }

    /**
     * Permanently deletes the user. A student takes their own data with them (homework, goals, vocab,
     * progress, 1:1 lessons nobody else attends); a teacher takes only what no student uses, so a
     * student moved to another teacher keeps their history. Rows that survive lose the reference
     * (foreign keys are ON DELETE SET NULL). Stored files are purged after the commit.
     */
    fun hardDeleteUser(id: UUID, principal: UserPrincipal) {
        if (id == principal.id) throw BadRequestException("Admins cannot delete themselves", code = "ADMIN_SELF_ACTION")
        val keysToPurge = transaction {
            val user = loadById(id)
            val keys = listOfNotNull(user[UserTable.avatarUrl]) +
                deleteStudentData(id) +
                deleteUnassignedTeacherData(id)
            UserTable.deleteWhere { UserTable.id eq id }
            keys
        }
        keysToPurge.forEach { key ->
            runCatching { storage.delete(key) }.onFailure { log.warn("Failed to delete stored object {} of deleted user {}", key, id, it) }
        }
    }

    /** Deletes the 1:1 lessons only this student attends; returns the homework upload keys (homework and goals cascade). */
    private fun deleteStudentData(studentId: UUID): List<String> {
        val uploadKeys = (HomeworkUploadTable innerJoin HomeworkTable)
            .select(HomeworkUploadTable.storageKey)
            .where { HomeworkTable.studentId eq studentId }
            .map { it[HomeworkUploadTable.storageKey] }

        val attended = LessonStudentTable.select(LessonStudentTable.lessonId)
            .where { LessonStudentTable.studentId eq studentId }
            .map { it[LessonStudentTable.lessonId].value }
        if (attended.isNotEmpty()) {
            val withOthers = LessonStudentTable.select(LessonStudentTable.lessonId)
                .where { (LessonStudentTable.lessonId inList attended) and (LessonStudentTable.studentId neq studentId) }
                .map { it[LessonStudentTable.lessonId].value }
                .toSet()
            val solo = attended - withOthers
            if (solo.isNotEmpty()) {
                LessonTable.deleteWhere { (LessonTable.id inList solo) and (LessonTable.type eq LessonType.ONE_ON_ONE) }
            }
        }
        return uploadKeys
    }

    /**
     * Deletes the teacher's rows no student uses: lessons without students, assignments without
     * homework, documents not shared with a student or linked to a surviving lesson, vocab entries
     * no student or surviving lesson / document / homework holds, and materials / folders not shared
     * with, attached for, or assigned to a student. Returns the storage keys of the deleted materials.
     */
    private fun deleteUnassignedTeacherData(teacherId: UUID): List<String> {
        LessonTable.deleteWhere {
            (LessonTable.teacherId eq teacherId) and
                (LessonTable.id notInSubQuery LessonStudentTable.select(LessonStudentTable.lessonId))
        }
        AssignmentTable.deleteWhere {
            (AssignmentTable.teacherId eq teacherId) and
                (AssignmentTable.id notInSubQuery HomeworkTable.select(HomeworkTable.assignmentId))
        }
        DocumentTable.deleteWhere {
            (DocumentTable.ownerId eq teacherId) and
                (DocumentTable.id notInSubQuery DocumentStudentTable.select(DocumentStudentTable.documentId)) and
                (DocumentTable.id notInSubQuery DocumentLessonTable.select(DocumentLessonTable.documentId))
        }

        val entryIds = VocabEntryTable.select(VocabEntryTable.id)
            .where { VocabEntryTable.teacherId eq teacherId }
            .map { it[VocabEntryTable.id].value }
        val heldEntries = buildSet {
            StudentVocabTable.select(StudentVocabTable.vocabEntryId)
                .where { StudentVocabTable.vocabEntryId inList entryIds }
                .forEach { add(it[StudentVocabTable.vocabEntryId].value) }
            LessonVocabTable.select(LessonVocabTable.vocabEntryId)
                .where { LessonVocabTable.vocabEntryId inList entryIds }
                .forEach { add(it[LessonVocabTable.vocabEntryId].value) }
        }
        val deletedEntries = entryIds.filter { entryId ->
            entryId !in heldEntries && !inSurvivingBlocks("""[{"type":"vocab_table","rows":[{"vocabEntryId":"$entryId"}]}]""")
        }
        if (deletedEntries.isNotEmpty()) {
            VocabEntryTable.deleteWhere { VocabEntryTable.id inList deletedEntries }
        }

        // Folders shared with a student keep their whole subtree.
        val parentOf = MaterialFolderTable.select(MaterialFolderTable.id, MaterialFolderTable.parentFolderId)
            .where { MaterialFolderTable.teacherId eq teacherId }
            .associate { it[MaterialFolderTable.id].value to it[MaterialFolderTable.parentFolderId]?.value }
        val keptFolders = FolderShareTable.select(FolderShareTable.folderId)
            .where { FolderShareTable.folderId inList parentOf.keys }
            .map { it[FolderShareTable.folderId].value }
            .toMutableSet()
        do {
            val added = parentOf.filter { (folder, parent) -> parent in keptFolders && keptFolders.add(folder) }
        } while (added.isNotEmpty())

        val materials = MaterialTable.select(MaterialTable.id, MaterialTable.type, MaterialTable.url, MaterialTable.folderId)
            .where { MaterialTable.teacherId eq teacherId }
            .toList()
        val materialIds = materials.map { it[MaterialTable.id].value }
        val inUse = buildSet {
            MaterialShareTable.select(MaterialShareTable.materialId)
                .where { MaterialShareTable.materialId inList materialIds }
                .forEach { add(it[MaterialShareTable.materialId].value) }
            // Only links to lessons that survived (studentless ones were deleted above).
            LessonMaterialTable.select(LessonMaterialTable.materialId)
                .where { LessonMaterialTable.materialId inList materialIds }
                .forEach { add(it[LessonMaterialTable.materialId].value) }
        }
        val deletedMaterials = materials.filter { row ->
            val materialId = row[MaterialTable.id].value
            materialId !in inUse &&
                row[MaterialTable.folderId]?.value !in keptFolders &&
                AssignmentItemTable.select(AssignmentItemTable.id).where { AssignmentItemTable.materialId eq materialId }.empty() &&
                !inSurvivingBlocks("""[{"type":"media","materialId":"$materialId"}]""")
        }
        if (deletedMaterials.isNotEmpty()) {
            MaterialTable.deleteWhere { MaterialTable.id inList deletedMaterials.map { it[MaterialTable.id].value } }
        }

        // parent_folder_id is ON DELETE RESTRICT: detach before deleting so order doesn't matter.
        val deletedFolders = parentOf.keys - keptFolders
        if (deletedFolders.isNotEmpty()) {
            MaterialFolderTable.update({ MaterialFolderTable.parentFolderId inList deletedFolders }) {
                it[parentFolderId] = null
            }
            MaterialFolderTable.deleteWhere { MaterialFolderTable.id inList deletedFolders }
        }

        return deletedMaterials
            .filter { it[MaterialTable.type] != MaterialType.LINK }
            .map { it[MaterialTable.url] }
    }

    /** True when a remaining document or homework snapshot has a block containing [pattern]. */
    private fun inSurvivingBlocks(pattern: String): Boolean =
        !DocumentTable.select(DocumentTable.id).where { DocumentTable.blocks.contains(pattern) }.empty() ||
            !AssignmentItemTable.select(AssignmentItemTable.id).where { AssignmentItemTable.blocks.contains(pattern) }.empty()

    fun unlockUser(id: UUID): AdminUserResponse = transaction {
        loadById(id)
        UserTable.update({ UserTable.id eq id }) {
            it[failedLoginAttempts] = 0
            it[lockedUntil] = null
            it[updatedAt] = OffsetDateTime.now()
        }
        toResponse(loadById(id))
    }

    fun setPassword(id: UUID, newPassword: String): Unit = transaction {
        loadById(id)
        UserTable.update({ UserTable.id eq id }) {
            it[passwordHash] = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            it[failedLoginAttempts] = 0
            it[lockedUntil] = null
            it[updatedAt] = OffsetDateTime.now()
        }
        RefreshTokenTable.deleteWhere { userId eq id }
    }

    fun setStatus(id: UUID, principal: UserPrincipal, newStatus: UserStatus): AdminUserResponse = transaction {
        if (id == principal.id && newStatus != UserStatus.ACTIVE)
            throw BadRequestException("Admins cannot deactivate themselves", code = "ADMIN_SELF_ACTION")

        val current = loadById(id)
        UserTable.update({ UserTable.id eq id }) {
            it[status] = newStatus
            it[updatedAt] = OffsetDateTime.now()
        }
        if (newStatus == UserStatus.INACTIVE && current[UserTable.status] != UserStatus.INACTIVE) {
            RefreshTokenTable.deleteWhere { userId eq id }
        }
        toResponse(loadById(id))
    }

    private fun loadById(id: UUID): ResultRow =
        UserTable.selectAll()
            .where { UserTable.id eq id }
            .singleOrNotFound("User")

    private fun toResponse(row: ResultRow): AdminUserResponse {
        val userId = row[UserTable.id].value
        val avatarKey = row[UserTable.avatarUrl]
        val avatarUrl = avatarKey?.let {
            val cacheBust = it.substringAfterLast('/')
            "/api/v1/users/$userId/avatar?v=$cacheBust"
        }
        return AdminUserResponse(
            id = userId.toString(),
            email = row[UserTable.email],
            firstName = row[UserTable.firstName],
            lastName = row[UserTable.lastName],
            initials = row[UserTable.initials],
            role = row[UserTable.role],
            avatarUrl = avatarUrl,
            level = row[UserTable.level],
            status = row[UserTable.status],
            locale = row[UserTable.locale],
            nativeLanguage = row[UserTable.nativeLanguage],
            localeConfirmedAt = row[UserTable.localeConfirmedAt],
            createdAt = row[UserTable.createdAt],
            updatedAt = row[UserTable.updatedAt],
            failedLoginAttempts = row[UserTable.failedLoginAttempts],
            lockedUntil = row[UserTable.lockedUntil],
            lastLoginAt = row[UserTable.lastLoginAt],
        )
    }
}

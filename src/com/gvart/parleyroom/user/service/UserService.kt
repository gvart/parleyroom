package com.gvart.parleyroom.user.service

import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.user.transfer.UpdateProfileRequest
import com.gvart.parleyroom.user.transfer.UserListResponse
import com.gvart.parleyroom.user.transfer.UserResponse
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.UUID

class UserService(
    private val storage: StorageService,
) {
    private val log = LoggerFactory.getLogger(UserService::class.java)

    companion object {
        val ALLOWED_AVATAR_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
        )
        const val MAX_AVATAR_SIZE_BYTES = 5L * 1024 * 1024
    }

    fun findAllUsers(principal: UserPrincipal, page: PageRequest): UserListResponse = transaction {
        val baseQuery: Query = when (principal.role) {
            UserRole.ADMIN -> UserTable.selectAll()
            UserRole.TEACHER -> UserTable.join(TeacherStudentTable, JoinType.INNER)
            { UserTable.id eq TeacherStudentTable.studentId }
                .selectAll()
                .where { TeacherStudentTable.teacherId eq principal.id }
            UserRole.STUDENT -> UserTable.join(TeacherStudentTable, JoinType.INNER)
            { UserTable.id eq TeacherStudentTable.teacherId }
                .selectAll()
                .where { TeacherStudentTable.studentId eq principal.id }
        }

        val total = baseQuery.count()
        val users = baseQuery
            .limit(page.pageSize)
            .offset(page.offset)
            .map { UserListResponse.User(
                it[UserTable.id].value.toString(),
                it[UserTable.firstName],
                it[UserTable.lastName],
                it[UserTable.initials],
                it[UserTable.role],
                avatarUrl(it[UserTable.id].value, it[UserTable.avatarUrl]),
                it[UserTable.level],
                it[UserTable.status],
                it[UserTable.createdAt],
                it[UserTable.locale],
            ) }

        UserListResponse(
            users = users,
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    fun getProfile(principal: UserPrincipal): UserResponse = transaction {
        val row = UserTable.selectAll()
            .where { UserTable.id eq principal.id }
            .singleOrNull() ?: throw NotFoundException("User not found")
        toResponse(row)
    }

    fun updateProfile(principal: UserPrincipal, request: UpdateProfileRequest): UserResponse = transaction {
        val current = UserTable.selectAll()
            .where { UserTable.id eq principal.id }
            .singleOrNull() ?: throw NotFoundException("User not found")

        val newFirstName = request.firstName?.trim() ?: current[UserTable.firstName]
        val newLastName = request.lastName?.trim() ?: current[UserTable.lastName]
        val nameChanged = request.firstName != null || request.lastName != null

        UserTable.update({ UserTable.id eq principal.id }) {
            if (request.firstName != null) it[firstName] = newFirstName
            if (request.lastName != null) it[lastName] = newLastName
            if (nameChanged) it[initials] = "${newFirstName[0]}${newLastName[0]}"
            if (request.locale != null) it[locale] = request.locale
            if (request.level != null) it[level] = request.level
            it[updatedAt] = OffsetDateTime.now()
        }

        val updated = UserTable.selectAll()
            .where { UserTable.id eq principal.id }
            .single()
        toResponse(updated)
    }

    fun updateAvatar(
        principal: UserPrincipal,
        fileName: String,
        contentType: String,
        size: Long,
        stream: InputStream,
    ): UserResponse {
        if (contentType !in ALLOWED_AVATAR_CONTENT_TYPES) {
            throw BadRequestException("Avatar content type must be one of ${ALLOWED_AVATAR_CONTENT_TYPES.joinToString()}")
        }
        if (size <= 0 || size > MAX_AVATAR_SIZE_BYTES) {
            throw BadRequestException("Avatar must be between 1 byte and $MAX_AVATAR_SIZE_BYTES bytes")
        }

        val newKey = storage.buildAvatarKey(principal.id, fileName)
        storage.upload(newKey, contentType, stream, size)

        val oldKey = runCatching {
            transaction {
                val current = UserTable.selectAll()
                    .where { UserTable.id eq principal.id }
                    .singleOrNull() ?: throw NotFoundException("User not found")
                val previous = current[UserTable.avatarUrl]
                UserTable.update({ UserTable.id eq principal.id }) {
                    it[avatarUrl] = newKey
                    it[updatedAt] = OffsetDateTime.now()
                }
                previous
            }
        }.getOrElse { e ->
            runCatching { storage.delete(newKey) }
                .onFailure { log.warn("Failed to clean up orphaned avatar after DB update failure: {}", newKey, it) }
            throw e
        }

        if (oldKey != null && oldKey != newKey) {
            runCatching { storage.delete(oldKey) }
                .onFailure { log.warn("Failed to delete previous avatar {}", oldKey, it) }
        }

        return getProfile(principal)
    }

    fun unlinkTelegram(principal: UserPrincipal) = transaction {
        val updated = UserTable.update({ UserTable.id eq principal.id }) {
            it[telegramId] = null
            it[telegramUsername] = null
            it[updatedAt] = OffsetDateTime.now()
        }
        if (updated == 0) throw NotFoundException("User not found")
    }

    fun deleteAvatar(principal: UserPrincipal): UserResponse {
        val oldKey = transaction {
            val current = UserTable.selectAll()
                .where { UserTable.id eq principal.id }
                .singleOrNull() ?: throw NotFoundException("User not found")
            val previous = current[UserTable.avatarUrl]
            if (previous != null) {
                UserTable.update({ UserTable.id eq principal.id }) {
                    it[avatarUrl] = null
                    it[updatedAt] = OffsetDateTime.now()
                }
            }
            previous
        }

        if (oldKey != null) {
            runCatching { storage.delete(oldKey) }
                .onFailure { log.warn("Failed to delete avatar {}", oldKey, it) }
        }

        return getProfile(principal)
    }

    fun getAvatarKey(userId: UUID): String {
        val key = transaction {
            UserTable.selectAll()
                .where { UserTable.id eq userId }
                .singleOrNull()
                ?.get(UserTable.avatarUrl)
        }
        return key ?: throw NotFoundException("Avatar not found")
    }

    private fun avatarUrl(userId: UUID, key: String?): String? =
        key?.let {
            val cacheBust = it.substringAfterLast('/')
            "/api/v1/users/$userId/avatar?v=$cacheBust"
        }

    private fun toResponse(row: ResultRow): UserResponse {
        val userId = row[UserTable.id].value
        return UserResponse(
            id = userId.toString(),
            email = row[UserTable.email],
            firstName = row[UserTable.firstName],
            lastName = row[UserTable.lastName],
            initials = row[UserTable.initials],
            role = row[UserTable.role],
            avatarUrl = avatarUrl(userId, row[UserTable.avatarUrl]),
            level = row[UserTable.level],
            status = row[UserTable.status],
            locale = row[UserTable.locale],
            createdAt = row[UserTable.createdAt],
            telegramId = row[UserTable.telegramId],
            telegramUsername = row[UserTable.telegramUsername],
        )
    }
}

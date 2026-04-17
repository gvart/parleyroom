package com.gvart.parleyroom.admin.service

import com.gvart.parleyroom.admin.transfer.ActivityStats
import com.gvart.parleyroom.admin.transfer.AdminCreateUserRequest
import com.gvart.parleyroom.admin.transfer.AdminStatsResponse
import com.gvart.parleyroom.admin.transfer.AdminUpdateUserRequest
import com.gvart.parleyroom.admin.transfer.AdminUserListResponse
import com.gvart.parleyroom.admin.transfer.AdminUserResponse
import com.gvart.parleyroom.admin.transfer.DomainStats
import com.gvart.parleyroom.admin.transfer.SecurityStats
import com.gvart.parleyroom.admin.transfer.UserStats
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.goal.data.LearningGoalTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.VocabularyWordTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
import java.util.UUID

class AdminService {

    fun listUsers(
        page: PageRequest,
        role: UserRole?,
        status: UserStatus?,
        search: String?,
    ): AdminUserListResponse = transaction {
        val filters: List<Op<Boolean>> = with(SqlExpressionBuilder) {
            buildList {
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
            .singleOrNull() ?: throw NotFoundException("User not found")
        toResponse(row)
    }

    fun createUser(request: AdminCreateUserRequest): AdminUserResponse = transaction {
        val email = request.email.trim()
        val firstName = request.firstName.trim()
        val lastName = request.lastName.trim()

        val exists = UserTable.selectAll()
            .where { UserTable.email eq email }
            .empty().not()
        if (exists) throw ConflictException("User with this email already exists")

        val newId = UserTable.insert {
            it[UserTable.email] = email
            it[UserTable.firstName] = firstName
            it[UserTable.lastName] = lastName
            it[passwordHash] = BCrypt.hashpw(request.password, BCrypt.gensalt())
            it[UserTable.role] = request.role
            it[initials] = "${firstName[0]}${lastName[0]}"
            if (request.level != null) it[level] = request.level
            if (request.locale != null) it[locale] = request.locale
            it[UserTable.status] = UserStatus.ACTIVE
        }[UserTable.id].value

        toResponse(loadById(newId))
    }

    fun updateUser(
        id: UUID,
        principal: UserPrincipal,
        request: AdminUpdateUserRequest,
    ): AdminUserResponse = transaction {
        val current = loadById(id)
        val currentRole = current[UserTable.role]
        val currentStatus = current[UserTable.status]

        if (id == principal.id) {
            if (request.role != null && request.role != currentRole)
                throw BadRequestException("Admins cannot change their own role")
            if (request.status != null && request.status != UserStatus.ACTIVE)
                throw BadRequestException("Admins cannot deactivate themselves")
        }

        val newEmail = request.email?.trim()
        if (newEmail != null && newEmail != current[UserTable.email]) {
            val emailTaken = UserTable.selectAll()
                .where { (UserTable.email eq newEmail) and (UserTable.id neq id) }
                .empty().not()
            if (emailTaken) throw ConflictException("User with this email already exists")
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
            it[updatedAt] = OffsetDateTime.now()
        }

        if (request.status == UserStatus.INACTIVE && currentStatus != UserStatus.INACTIVE) {
            RefreshTokenTable.deleteWhere { userId eq id }
        }

        toResponse(loadById(id))
    }

    fun softDeleteUser(id: UUID, principal: UserPrincipal): Unit = transaction {
        if (id == principal.id) throw BadRequestException("Admins cannot delete themselves")
        val current = loadById(id)
        UserTable.update({ UserTable.id eq id }) {
            it[status] = UserStatus.INACTIVE
            it[updatedAt] = OffsetDateTime.now()
        }
        if (current[UserTable.status] != UserStatus.INACTIVE) {
            RefreshTokenTable.deleteWhere { userId eq id }
        }
    }

    fun hardDeleteUser(id: UUID, principal: UserPrincipal): Unit = transaction {
        if (id == principal.id) throw BadRequestException("Admins cannot delete themselves")
        loadById(id)
        try {
            UserTable.deleteWhere { UserTable.id eq id }
        } catch (e: ExposedSQLException) {
            throw ConflictException(
                "User has associated data (lessons, homework, vocabulary, goals, or materials). " +
                    "Soft-delete instead, or remove the related records first."
            )
        }
    }

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
            throw BadRequestException("Admins cannot deactivate themselves")

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

    fun getStats(): AdminStatsResponse = transaction {
        val now = OffsetDateTime.now()

        val total = UserTable.selectAll().count()
        val byRole = UserRole.entries.associate { role ->
            role.name to UserTable.selectAll().where { UserTable.role eq role }.count()
        }
        val byStatus = UserStatus.entries.associate { status ->
            status.name to UserTable.selectAll().where { UserTable.status eq status }.count()
        }

        val currentlyLocked = UserTable.selectAll()
            .where { UserTable.lockedUntil.isNotNull() and (UserTable.lockedUntil greater now) }
            .count()
        val withFailedAttempts = UserTable.selectAll()
            .where { UserTable.failedLoginAttempts greater 0 }
            .count()
        val pendingInvitations = RegistrationTable.selectAll()
            .where {
                (RegistrationTable.used eq false) and
                    (RegistrationTable.expiresAt greaterEq now)
            }
            .count()

        val sevenDaysAgo = now.minusDays(7)
        val thirtyDaysAgo = now.minusDays(30)
        val registered7 = UserTable.selectAll()
            .where { UserTable.createdAt greaterEq sevenDaysAgo }
            .count()
        val registered30 = UserTable.selectAll()
            .where { UserTable.createdAt greaterEq thirtyDaysAgo }
            .count()
        val activeRefreshTokens = RefreshTokenTable.selectAll()
            .where { RefreshTokenTable.expiresAt greater now }
            .count()

        val lessons = LessonTable.selectAll().count()
        val homework = HomeworkTable.selectAll().count()
        val materials = MaterialTable.selectAll().count()
        val vocabularyWords = VocabularyWordTable.selectAll().count()
        val learningGoals = LearningGoalTable.selectAll().count()

        AdminStatsResponse(
            users = UserStats(
                total = total,
                byRole = byRole,
                byStatus = byStatus,
            ),
            security = SecurityStats(
                currentlyLocked = currentlyLocked,
                withFailedAttempts = withFailedAttempts,
                pendingInvitations = pendingInvitations,
            ),
            activity = ActivityStats(
                registeredLast7Days = registered7,
                registeredLast30Days = registered30,
                activeRefreshTokens = activeRefreshTokens,
            ),
            domain = DomainStats(
                lessons = lessons,
                homework = homework,
                materials = materials,
                vocabularyWords = vocabularyWords,
                learningGoals = learningGoals,
            ),
        )
    }

    private fun loadById(id: UUID): ResultRow =
        UserTable.selectAll()
            .where { UserTable.id eq id }
            .singleOrNull() ?: throw NotFoundException("User not found")

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
            createdAt = row[UserTable.createdAt],
            updatedAt = row[UserTable.updatedAt],
            failedLoginAttempts = row[UserTable.failedLoginAttempts],
            lockedUntil = row[UserTable.lockedUntil],
        )
    }
}

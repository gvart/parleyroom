package com.gvart.parleyroom.registration.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.registration.data.PasswordResetTable
import com.gvart.parleyroom.registration.transfer.ResetPasswordResponse
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
import java.util.UUID

private const val PASSWORD_RESET_EXPIRY_HOURS = 24L

class PasswordResetService {

    fun requestResetForSelf(principal: UserPrincipal): ResetPasswordResponse =
        createResetToken(principal.id)

    fun requestResetForUser(userId: UUID, principal: UserPrincipal): ResetPasswordResponse {
        if (principal.role != UserRole.ADMIN) throw ForbiddenException("Only admins can reset other users' passwords")
        return transaction {
            val exists = UserTable.selectAll()
                .where { UserTable.id eq userId }
                .empty().not()
            if (!exists) throw NotFoundException("User not found", code = "USER_NOT_FOUND")
            createResetToken(userId)
        }
    }

    private fun createResetToken(userId: UUID): ResetPasswordResponse = transaction {
        PasswordResetTable.update({
            (PasswordResetTable.userId eq userId) and (PasswordResetTable.used eq false)
        }) {
            it[used] = true
        }

        val token = UUID.randomUUID()
        PasswordResetTable.insert {
            it[PasswordResetTable.userId] = userId
            it[PasswordResetTable.token] = token
            it[expiresAt] = OffsetDateTime.now().plusHours(PASSWORD_RESET_EXPIRY_HOURS)
        }
        ResetPasswordResponse(token.toString())
    }

    fun resetPassword(token: String, newPassword: String) = transaction {
        val resetEntry = PasswordResetTable.selectAll()
            .where { PasswordResetTable.token eq UUID.fromString(token) }
            .singleOrNull()

        if (resetEntry == null) throw NotFoundException("Invalid reset token", code = "RESET_TOKEN_INVALID")
        if (resetEntry[PasswordResetTable.used]) throw BadRequestException("Reset token already used", code = "RESET_TOKEN_USED")
        if (resetEntry[PasswordResetTable.expiresAt].isBefore(OffsetDateTime.now())) throw BadRequestException("Reset token expired", code = "RESET_TOKEN_EXPIRED")

        val userId = resetEntry[PasswordResetTable.userId]

        UserTable.update({ UserTable.id eq userId }) {
            it[passwordHash] = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            it[updatedAt] = OffsetDateTime.now()
            it[failedLoginAttempts] = 0
            it[lockedUntil] = null
        }

        PasswordResetTable.update({ PasswordResetTable.id eq resetEntry[PasswordResetTable.id] }) {
            it[used] = true
        }

        RefreshTokenTable.deleteWhere { RefreshTokenTable.userId eq userId }
    }
}
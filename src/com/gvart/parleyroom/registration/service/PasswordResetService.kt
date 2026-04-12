package com.gvart.parleyroom.registration.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.registration.data.PasswordResetTable
import com.gvart.parleyroom.registration.transfer.ResetPasswordResponse
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
import java.util.UUID

private const val PASSWORD_RESET_EXPIRY_HOURS = 24L

class PasswordResetService {

    fun requestResetForSelf(principal: UserPrincipal): ResetPasswordResponse =
        createResetToken(principal.id)

    fun requestResetForUser(userId: UUID, principal: UserPrincipal): ResetPasswordResponse {
        if (principal.role != UserRole.ADMIN) throw ForbiddenException("Only admins can reset other users' passwords")
        transaction {
            val exists = UserTable.selectAll()
                .where { UserTable.id eq userId }
                .empty().not()
            if (!exists) throw NotFoundException("User not found")
        }
        return createResetToken(userId)
    }

    private fun createResetToken(userId: UUID): ResetPasswordResponse = transaction {
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

        if (resetEntry == null) throw NotFoundException("Invalid reset token")
        if (resetEntry[PasswordResetTable.used]) throw BadRequestException("Reset token already used")
        if (resetEntry[PasswordResetTable.expiresAt].isBefore(OffsetDateTime.now())) throw BadRequestException("Reset token expired")

        UserTable.update({ UserTable.id eq resetEntry[PasswordResetTable.userId] }) {
            it[passwordHash] = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            it[updatedAt] = OffsetDateTime.now()
        }

        PasswordResetTable.update({ PasswordResetTable.id eq resetEntry[PasswordResetTable.id] }) {
            it[used] = true
        }
    }
}
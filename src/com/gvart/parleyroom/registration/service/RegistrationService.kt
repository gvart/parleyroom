package com.gvart.parleyroom.registration.service

import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.registration.transfer.RegisterUserRequest
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.user.security.UserPrincipal

private const val INVITATION_EXPIRY_DAYS = 7L

class RegistrationService {

    fun inviteUser(request: InviteUserRequest, principal: UserPrincipal): InviteUserResponse {
        when (principal.role) {
            UserRole.STUDENT -> throw ForbiddenException("Students cannot invite users")
            UserRole.TEACHER -> {
                if (request.role != UserRole.STUDENT)
                    throw ForbiddenException("Teachers can only invite students")
            }
            UserRole.ADMIN -> {}
        }

        return transaction {
            val alreadyExists = UserTable.selectAll()
                .where { UserTable.email eq request.email }
                .empty().not()

            if (alreadyExists) throw ConflictException("User with this email already exists")

            val pendingInvitation = RegistrationTable.selectAll()
                .where {
                    (RegistrationTable.email eq request.email) and
                        (RegistrationTable.used eq false) and
                        (RegistrationTable.expiresAt greaterEq OffsetDateTime.now())
                }
                .empty().not()

            if (pendingInvitation) throw ConflictException("A pending invitation already exists for this email")

            val registrationToken = UUID.randomUUID().toString()
            val hashedToken = hashToken(registrationToken)
            RegistrationTable.insert {
                it[email] = request.email
                it[token] = hashedToken
                it[role] = request.role
                it[expiresAt] = OffsetDateTime.now().plusDays(INVITATION_EXPIRY_DAYS)
                it[invitedBy] = principal.id
            }

            InviteUserResponse(registrationToken)
        }
    }

    fun registerUser(request: RegisterUserRequest) = transaction {
        val hashedToken = hashToken(request.token)
        val entry = RegistrationTable.selectAll()
            .where { (RegistrationTable.token eq hashedToken).and { RegistrationTable.email eq request.email } }
            .singleOrNull()

        if (entry == null) throw NotFoundException("Registration entry is missing")
        if (entry[RegistrationTable.used]) throw BadRequestException("Registration link already used")
        if (entry[RegistrationTable.expiresAt].isBefore(OffsetDateTime.now())) throw BadRequestException("Registration link is expired")

        val emailExists = UserTable.selectAll()
            .where { UserTable.email eq request.email }
            .empty().not()
        if (emailExists) throw ConflictException("User with this email already exists")

        val trimmedFirstName = request.firstName.trim()
        val trimmedLastName = request.lastName.trim()

        val newUserId = UserTable.insertAndGetId {
            it[email] = entry[RegistrationTable.email]
            it[firstName] = trimmedFirstName
            it[lastName] = trimmedLastName
            it[passwordHash] = BCrypt.hashpw(request.password, BCrypt.gensalt())
            it[role] = entry[RegistrationTable.role]
            it[initials] = "${trimmedFirstName[0]}${trimmedLastName[0]}"
        }

        RegistrationTable.update({ RegistrationTable.id eq entry[RegistrationTable.id] }) {
            it[RegistrationTable.used] = true
        }

        val invitedById = entry[RegistrationTable.invitedBy]
        val newUserRole = entry[RegistrationTable.role]
        if (invitedById != null && newUserRole == UserRole.STUDENT) {
            val inviter = UserTable.selectAll()
                .where { UserTable.id eq invitedById }
                .singleOrNull()

            if (inviter != null && inviter[UserTable.role] == UserRole.TEACHER) {
                TeacherStudentTable.insert {
                    it[teacherId] = invitedById
                    it[studentId] = newUserId
                    it[status] = UserStatus.ACTIVE
                    it[startedAt] = OffsetDateTime.now()
                }
            }
        }
    }

    companion object {
        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(token.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
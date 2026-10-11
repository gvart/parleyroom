package com.gvart.parleyroom.registration.service

import com.gvart.parleyroom.admin.service.AppSettingsService
import com.gvart.parleyroom.email.service.NotificationMailer
import com.gvart.parleyroom.email.template.emailLanguageFor
import com.gvart.parleyroom.material.data.FolderShareTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.registration.data.RegistrationMaterialTable
import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.registration.transfer.InviteListResponse
import com.gvart.parleyroom.registration.transfer.InviteStatus
import com.gvart.parleyroom.registration.transfer.InviteSummary
import com.gvart.parleyroom.registration.transfer.InviterSummary
import com.gvart.parleyroom.registration.transfer.RegisterUserRequest
import com.gvart.parleyroom.user.data.DEFAULT_NATIVE_LANGUAGE
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.data.requireSupportedNativeLanguage
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
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

private const val ONBOARDING_FOLDER_PREFIX = "Onboarding – "

/** The user created by [RegistrationService.registerUser]; [teacherId] is set when a teacher link was created. */
data class RegisteredUser(val userId: UUID, val teacherId: UUID?)

/** An invite row with its fresh plaintext token, ready to be emailed. */
private data class IssuedInvite(
    val id: UUID,
    val email: String,
    val role: UserRole,
    val nativeLanguage: String?,
    val invitedBy: UUID?,
    val token: String,
    val expiresAt: OffsetDateTime,
)

class RegistrationService(
    private val appSettings: AppSettingsService,
    private val mailer: NotificationMailer,
) {
    private val log = LoggerFactory.getLogger(RegistrationService::class.java)

    /** Creates the invite, then (after commit) emails it; a failed send only shows as emailSent = false. */
    suspend fun inviteUser(request: InviteUserRequest, principal: UserPrincipal): InviteUserResponse {
        when (principal.role) {
            UserRole.STUDENT -> throw ForbiddenException("Students cannot invite users")
            UserRole.TEACHER -> {
                if (request.role != UserRole.STUDENT)
                    throw ForbiddenException("Teachers can only invite students")
            }
            UserRole.ADMIN -> {}
        }

        request.nativeLanguage?.let(::requireSupportedNativeLanguage)

        val materialIds = request.materialIds.map(UUID::fromString).distinct()
        if (materialIds.isNotEmpty() && principal.role != UserRole.TEACHER)
            throw BadRequestException("Only teachers can attach materials to an invite", code = "INVITE_MATERIALS_NOT_ALLOWED")

        val expiryDays = appSettings.inviteExpiryDays().toLong()
        val invite = transaction {
            val alreadyExists = UserTable.selectAll()
                .where { UserTable.email eq request.email }
                .empty().not()

            if (alreadyExists) throw ConflictException("User with this email already exists", code = "EMAIL_ALREADY_EXISTS")

            val pendingInvitation = RegistrationTable.selectAll()
                .where {
                    (RegistrationTable.email eq request.email) and
                        (RegistrationTable.used eq false) and
                        (RegistrationTable.expiresAt greaterEq OffsetDateTime.now())
                }
                .empty().not()

            if (pendingInvitation) throw ConflictException("A pending invitation already exists for this email", code = "INVITATION_ALREADY_PENDING")

            val registrationToken = UUID.randomUUID().toString()
            val hashedToken = hashToken(registrationToken)
            val expiry = OffsetDateTime.now().plusDays(expiryDays)
            val language = if (request.role == UserRole.STUDENT) request.nativeLanguage ?: DEFAULT_NATIVE_LANGUAGE else null
            val registrationId = RegistrationTable.insertAndGetId {
                it[email] = request.email
                it[token] = hashedToken
                it[role] = request.role
                it[expiresAt] = expiry
                it[invitedBy] = principal.id
                if (request.role == UserRole.STUDENT) {
                    it[nativeLanguage] = language
                    it[level] = request.level
                }
            }

            if (materialIds.isNotEmpty()) attachMaterials(registrationId.value, materialIds, principal.id, request.email)

            IssuedInvite(registrationId.value, request.email, request.role, language, principal.id, registrationToken, expiry)
        }

        return InviteUserResponse(invite.token, invite.expiresAt, emailSent = sendInviteEmail(invite, principal.id))
    }

    /** Admins see every invite, teachers the ones they sent; newest first. */
    fun listInvites(principal: UserPrincipal, status: InviteStatus?): InviteListResponse {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Students cannot list invites")

        return transaction {
            val now = OffsetDateTime.now()
            val byStatus: Op<Boolean> = when (status) {
                null -> Op.TRUE
                InviteStatus.USED -> RegistrationTable.used eq true
                InviteStatus.EXPIRED -> (RegistrationTable.used eq false) and (RegistrationTable.expiresAt less now)
                InviteStatus.PENDING -> (RegistrationTable.used eq false) and (RegistrationTable.expiresAt greaterEq now)
            }
            val byInviter: Op<Boolean> =
                if (principal.role == UserRole.TEACHER) RegistrationTable.invitedBy eq principal.id else Op.TRUE

            val invites = RegistrationTable.join(UserTable, JoinType.LEFT, RegistrationTable.invitedBy, UserTable.id)
                .selectAll()
                .where { byStatus and byInviter }
                .orderBy(RegistrationTable.createdAt to SortOrder.DESC)
                .map { row ->
                    InviteSummary(
                        id = row[RegistrationTable.id].value.toString(),
                        email = row[RegistrationTable.email],
                        role = row[RegistrationTable.role],
                        status = when {
                            row[RegistrationTable.used] -> InviteStatus.USED
                            row[RegistrationTable.expiresAt].isBefore(now) -> InviteStatus.EXPIRED
                            else -> InviteStatus.PENDING
                        },
                        createdAt = row[RegistrationTable.createdAt],
                        expiresAt = row[RegistrationTable.expiresAt],
                        lastSentAt = row[RegistrationTable.lastSentAt],
                        invitedBy = row.getOrNull(UserTable.id)?.let { id ->
                            InviterSummary(id.value.toString(), "${row[UserTable.firstName]} ${row[UserTable.lastName]}")
                        },
                    )
                }
            InviteListResponse(invites)
        }
    }

    /**
     * Rotates the token of an unused invite (the old link stops working), restarts its expiry
     * and emails it again. Expired invites can be resent.
     */
    suspend fun resendInvite(id: UUID, principal: UserPrincipal): InviteUserResponse {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Students cannot resend invites")

        val expiryDays = appSettings.inviteExpiryDays().toLong()
        val invite = transaction {
            val row = RegistrationTable.selectAll().where { RegistrationTable.id eq id }.singleOrNull()
                ?: throw NotFoundException("Invitation not found", code = "INVITATION_NOT_FOUND")
            val invitedBy = row[RegistrationTable.invitedBy]?.value
            if (principal.role == UserRole.TEACHER && invitedBy != principal.id)
                throw ForbiddenException("Teachers can only resend their own invites")
            if (row[RegistrationTable.used]) throw BadRequestException("Invitation already used", code = "INVITATION_ALREADY_USED")

            val email = row[RegistrationTable.email]
            if (UserTable.selectAll().where { UserTable.email eq email }.empty().not())
                throw ConflictException("User with this email already exists", code = "EMAIL_ALREADY_EXISTS")

            val registrationToken = UUID.randomUUID().toString()
            val expiry = OffsetDateTime.now().plusDays(expiryDays)
            RegistrationTable.update({ RegistrationTable.id eq id }) {
                it[token] = hashToken(registrationToken)
                it[expiresAt] = expiry
            }
            IssuedInvite(id, email, row[RegistrationTable.role], row[RegistrationTable.nativeLanguage], invitedBy, registrationToken, expiry)
        }

        return InviteUserResponse(invite.token, invite.expiresAt, emailSent = sendInviteEmail(invite, principal.id))
    }

    /**
     * Emails the invite in the invitee's native language (students) or the inviter's UI locale (others),
     * signed by the original inviter, or by [senderId] when that account is gone. Never throws.
     */
    private suspend fun sendInviteEmail(invite: IssuedInvite, senderId: UUID): Boolean {
        val sent = runCatching {
            val inviter = transaction {
                UserTable.selectAll().where { UserTable.id eq (invite.invitedBy ?: senderId) }.singleOrNull()
                    ?: UserTable.selectAll().where { UserTable.id eq senderId }.single()
            }
            mailer.sendInvite(
                email = invite.email,
                language = emailLanguageFor(invite.role, inviter[UserTable.locale], invite.nativeLanguage),
                inviterName = "${inviter[UserTable.firstName]} ${inviter[UserTable.lastName]}",
                role = invite.role,
                inviteToken = invite.token,
                expiresAt = invite.expiresAt,
                registrationId = invite.id,
            )
        }.getOrElse { e ->
            log.warn("Invite email for registration ${invite.id} failed", e)
            false
        }
        if (sent) transaction {
            RegistrationTable.update({ RegistrationTable.id eq invite.id }) { it[lastSentAt] = OffsetDateTime.now() }
        }
        return sent
    }

    fun registerUser(request: RegisterUserRequest): RegisteredUser = transaction {
        val hashedToken = hashToken(request.token)
        val entry = RegistrationTable.selectAll()
            .where { (RegistrationTable.token eq hashedToken).and { RegistrationTable.email eq request.email } }
            .singleOrNull()

        if (entry == null) throw NotFoundException("Registration entry is missing", code = "REGISTRATION_LINK_INVALID")
        if (entry[RegistrationTable.used]) throw BadRequestException("Registration link already used", code = "REGISTRATION_LINK_USED")
        if (entry[RegistrationTable.expiresAt].isBefore(OffsetDateTime.now())) throw BadRequestException("Registration link is expired", code = "REGISTRATION_LINK_EXPIRED")

        val emailExists = UserTable.selectAll()
            .where { UserTable.email eq request.email }
            .empty().not()
        if (emailExists) throw ConflictException("User with this email already exists", code = "EMAIL_ALREADY_EXISTS")

        val trimmedFirstName = request.firstName.trim()
        val trimmedLastName = request.lastName.trim()

        val newUserId = UserTable.insertAndGetId {
            it[email] = entry[RegistrationTable.email]
            it[firstName] = trimmedFirstName
            it[lastName] = trimmedLastName
            it[passwordHash] = BCrypt.hashpw(request.password, BCrypt.gensalt())
            it[role] = entry[RegistrationTable.role]
            it[initials] = "${trimmedFirstName[0]}${trimmedLastName[0]}"
            if (entry[RegistrationTable.role] == UserRole.STUDENT) {
                it[nativeLanguage] = entry[RegistrationTable.nativeLanguage] ?: DEFAULT_NATIVE_LANGUAGE
                it[level] = entry[RegistrationTable.level]
            }
        }

        RegistrationTable.update({ RegistrationTable.id eq entry[RegistrationTable.id] }) {
            it[RegistrationTable.used] = true
            it[RegistrationTable.userId] = newUserId
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
                shareInviteMaterials(entry[RegistrationTable.id].value, invitedById.value, newUserId.value)
                return@transaction RegisteredUser(newUserId.value, invitedById.value)
            }
        }
        RegisteredUser(newUserId.value, null)
    }

    /**
     * Validates the inviter owns every material (none of them links), links them to the
     * registration and files them into the teacher's "Onboarding – <email>" root folder.
     */
    private fun attachMaterials(registrationId: UUID, materialIds: List<UUID>, teacherId: UUID, email: String) {
        val rows = MaterialTable.selectAll()
            .where { (MaterialTable.id inList materialIds) and (MaterialTable.teacherId eq teacherId) }
            .toList()
        if (rows.size != materialIds.size) throw NotFoundException("One or more materials not found", code = "MATERIAL_NOT_FOUND")
        if (rows.any { it[MaterialTable.type] == MaterialType.LINK })
            throw BadRequestException("Links cannot be attached to an invite", code = "INVITE_MATERIAL_LINK_NOT_ALLOWED")

        materialIds.forEach { id ->
            RegistrationMaterialTable.insert {
                it[RegistrationMaterialTable.registrationId] = registrationId
                it[materialId] = id
            }
        }

        val folderId = onboardingFolder(teacherId, "$ONBOARDING_FOLDER_PREFIX$email".take(255))
        MaterialTable.update({ MaterialTable.id inList materialIds }) {
            it[MaterialTable.folderId] = folderId
        }
    }

    /** The teacher's root folder with this name (matched case-insensitively, like folder uniqueness), created if missing. */
    private fun onboardingFolder(teacherId: UUID, name: String): UUID =
        MaterialFolderTable.selectAll()
            .where {
                (MaterialFolderTable.teacherId eq teacherId) and
                    MaterialFolderTable.parentFolderId.isNull() and
                    (MaterialFolderTable.name.lowerCase() eq name.lowercase())
            }
            .firstOrNull()?.get(MaterialFolderTable.id)?.value
            ?: OffsetDateTime.now().let { now ->
                MaterialFolderTable.insertAndGetId {
                    it[MaterialFolderTable.teacherId] = teacherId
                    it[MaterialFolderTable.name] = name
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
            }

    /**
     * Shares the invite's still-existing materials of the inviter with the new student, without notifications,
     * plus the inviter's onboarding folder(s) they still live in, so the student sees them in the folder tree.
     */
    private fun shareInviteMaterials(registrationId: UUID, teacherId: UUID, studentId: UUID) {
        val rows = (RegistrationMaterialTable innerJoin MaterialTable)
            .select(RegistrationMaterialTable.materialId, MaterialTable.folderId)
            .where { (RegistrationMaterialTable.registrationId eq registrationId) and (MaterialTable.teacherId eq teacherId) }
            .toList()
        val now = OffsetDateTime.now()
        rows.forEach { row ->
            MaterialShareTable.insertIgnore {
                it[materialId] = row[RegistrationMaterialTable.materialId].value
                it[MaterialShareTable.studentId] = studentId
                it[sharedBy] = teacherId
                it[sharedAt] = now
            }
        }

        val folderIds = rows.mapNotNull { it[MaterialTable.folderId]?.value }.distinct()
        if (folderIds.isEmpty()) return
        MaterialFolderTable.select(MaterialFolderTable.id)
            .where {
                (MaterialFolderTable.id inList folderIds) and
                    (MaterialFolderTable.teacherId eq teacherId) and
                    (MaterialFolderTable.name like "$ONBOARDING_FOLDER_PREFIX%")
            }
            .forEach { row ->
                FolderShareTable.insertIgnore {
                    it[folderId] = row[MaterialFolderTable.id]
                    it[FolderShareTable.studentId] = studentId
                    it[sharedBy] = teacherId
                    it[sharedAt] = now
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
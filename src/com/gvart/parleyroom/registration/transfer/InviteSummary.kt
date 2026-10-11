package com.gvart.parleyroom.registration.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.user.data.UserRole
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** Derived: used → USED; else expiresAt in the past → EXPIRED; else PENDING. */
enum class InviteStatus { PENDING, EXPIRED, USED }

@Serializable
data class InviterSummary(val id: String, val name: String)

@Serializable
data class InviteSummary(
    val id: String,
    val email: String,
    val role: UserRole,
    val status: InviteStatus,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val expiresAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastSentAt: OffsetDateTime?,
    /** Null when the inviter's account was deleted (or for seeded invites). */
    val invitedBy: InviterSummary?,
)

@Serializable
data class InviteListResponse(val invites: List<InviteSummary>)

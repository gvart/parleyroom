package com.gvart.parleyroom.registration.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class InviteUserResponse(
    /** Plaintext registration token, for the copy-link fallback. */
    val token: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val expiresAt: OffsetDateTime,
    /** False when the invite email could not be sent; the invite itself still exists. */
    val emailSent: Boolean,
)

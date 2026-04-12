package com.gvart.parleyroom.registration.transfer

import kotlinx.serialization.Serializable

@Serializable
data class ResetPasswordResponse(
    val token: String,
)
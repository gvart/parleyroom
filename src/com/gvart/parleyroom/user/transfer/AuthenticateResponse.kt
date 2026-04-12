package com.gvart.parleyroom.user.transfer

import kotlinx.serialization.Serializable

@Serializable
data class AuthenticateResponse(
    val token: String,
)
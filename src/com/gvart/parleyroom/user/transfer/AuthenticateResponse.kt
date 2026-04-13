package com.gvart.parleyroom.user.transfer

import kotlinx.serialization.Serializable

@Serializable
data class AuthenticateResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresIn: Long,
)
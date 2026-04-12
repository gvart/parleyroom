package com.gvart.parleyroom.registration.transfer

import kotlinx.serialization.Serializable

@Serializable
data class InviteUserResponse(
    val token: String
)
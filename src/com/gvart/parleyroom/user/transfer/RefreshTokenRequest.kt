package com.gvart.parleyroom.user.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
) {
    fun validate(): ValidationResult =
        if (refreshToken.isBlank()) ValidationResult.Invalid(listOf("refreshToken can't be empty"))
        else ValidationResult.Valid
}
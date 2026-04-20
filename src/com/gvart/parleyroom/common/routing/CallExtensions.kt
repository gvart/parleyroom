package com.gvart.parleyroom.common.routing

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.UnauthorizedException
import com.gvart.parleyroom.user.security.UserPrincipal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import java.util.UUID

fun ApplicationCall.requirePrincipal(): UserPrincipal =
    principal<UserPrincipal>() ?: throw UnauthorizedException("Missing authentication")

fun ApplicationCall.getPathUUID(paramName: String = "id"): UUID {
    val raw = parameters[paramName] ?: throw BadRequestException("Missing path parameter: $paramName")
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid UUID in path parameter: $paramName")
    }
}

fun ApplicationCall.getQueryUUID(paramName: String): UUID? {
    val raw = request.queryParameters[paramName] ?: return null
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid UUID in query parameter: $paramName")
    }
}

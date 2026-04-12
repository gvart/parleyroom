package com.gvart.parleyroom.user.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gvart.parleyroom.common.transfer.exception.UnauthorizedException
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.JwtConfig
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant
import kotlin.time.toJavaDuration

class AuthenticationService(
    private val jwtConfig: JwtConfig
) {
    fun authenticate(request: AuthenticateRequest): AuthenticateResponse = transaction {
        val user = UserTable.selectAll()
            .where { UserTable.email eq request.email }
            .singleOrNull()

        if (user != null && BCrypt.checkpw(request.password, user[UserTable.passwordHash])) {
            val token = JWT.create()
                .withAudience(jwtConfig.audience)
                .withIssuer(jwtConfig.issuer)
                .withClaim("id", user[UserTable.id].value.toString())
                .withClaim("email", request.email)
                .withClaim("role", user[UserTable.role].name)
                .withExpiresAt(Instant.now().plus(jwtConfig.duration.toJavaDuration()))
                .sign(Algorithm.HMAC256(jwtConfig.secret))

            AuthenticateResponse(token)
        } else {
            throw UnauthorizedException("Invalid credentials")
        }
    }
}
package com.gvart.parleyroom.user.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gvart.parleyroom.common.transfer.exception.UnauthorizedException
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.JwtConfig
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import kotlin.time.toJavaDuration

class AuthenticationService(
    private val jwtConfig: JwtConfig,
) {
    private val secureRandom = SecureRandom()

    fun authenticate(request: AuthenticateRequest): AuthenticateResponse = transaction {
        val user = UserTable.selectAll()
            .where { UserTable.email eq request.email }
            .singleOrNull()

        if (user == null || !BCrypt.checkpw(request.password, user[UserTable.passwordHash])) {
            throw UnauthorizedException("Invalid credentials")
        }

        issueTokens(user)
    }

    fun refresh(request: RefreshTokenRequest): AuthenticateResponse {
        val hash = sha256(request.refreshToken)
        val (userId, expiresAt) = transaction {
            RefreshTokenTable.selectAll()
                .where { RefreshTokenTable.tokenHash eq hash }
                .singleOrNull()
                ?.let { it[RefreshTokenTable.userId].value to it[RefreshTokenTable.expiresAt] }
        } ?: throw UnauthorizedException("Invalid refresh token")

        if (expiresAt.toInstant().isBefore(Instant.now())) {
            transaction { RefreshTokenTable.deleteWhere { RefreshTokenTable.userId eq userId } }
            throw UnauthorizedException("Refresh token expired")
        }

        return transaction {
            val user = UserTable.selectAll()
                .where { UserTable.id eq userId }
                .singleOrNull() ?: throw UnauthorizedException("Invalid refresh token")
            issueTokens(user)
        }
    }

    private fun issueTokens(user: ResultRow): AuthenticateResponse {
        val userId = user[UserTable.id].value
        val accessToken = buildAccessToken(
            userId = userId,
            email = user[UserTable.email],
            role = user[UserTable.role],
        )
        val refreshToken = generateRefreshToken()
        persistRefreshToken(userId, refreshToken)
        return AuthenticateResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            accessTokenExpiresIn = jwtConfig.duration.inWholeSeconds,
        )
    }

    private fun buildAccessToken(userId: UUID, email: String, role: UserRole): String =
        JWT.create()
            .withAudience(jwtConfig.audience)
            .withIssuer(jwtConfig.issuer)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Instant.now())
            .withClaim("id", userId.toString())
            .withClaim("email", email)
            .withClaim("role", role.name)
            .withExpiresAt(Instant.now().plus(jwtConfig.duration.toJavaDuration()))
            .sign(Algorithm.HMAC256(jwtConfig.secret))

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun persistRefreshToken(userId: UUID, token: String) {
        val hash = sha256(token)
        val now = OffsetDateTime.now()
        val expiresAt = now.plus(jwtConfig.refreshDuration.toJavaDuration())

        RefreshTokenTable.deleteWhere { RefreshTokenTable.userId eq userId }
        RefreshTokenTable.insert {
            it[RefreshTokenTable.userId] = userId
            it[tokenHash] = hash
            it[RefreshTokenTable.expiresAt] = expiresAt
            it[createdAt] = now
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
package com.gvart.parleyroom.user.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gvart.parleyroom.common.transfer.exception.UnauthorizedException
import com.gvart.parleyroom.user.data.RefreshTokenTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.AuthLockoutConfig
import com.gvart.parleyroom.user.security.JwtConfig
import com.gvart.parleyroom.user.transfer.AuthenticateRequest
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.RefreshTokenRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
    private val lockoutConfig: AuthLockoutConfig,
) {
    private val secureRandom = SecureRandom()

    fun authenticate(request: AuthenticateRequest): AuthenticateResponse {
        val now = OffsetDateTime.now()
        val outcome: Result<AuthenticateResponse> = transaction {
            val user = UserTable.selectAll()
                .where { UserTable.email eq request.email }
                .forUpdate()
                .singleOrNull()
                ?: return@transaction Result.failure(UnauthorizedException("Invalid credentials"))

            val currentLock = user[UserTable.lockedUntil]
            if (currentLock != null && currentLock.isAfter(now)) {
                return@transaction Result.failure(UnauthorizedException("Account is locked. Try again later."))
            }

            val userId = user[UserTable.id].value
            if (!BCrypt.checkpw(request.password, user[UserTable.passwordHash])) {
                val newCount = user[UserTable.failedLoginAttempts] + 1
                if (newCount >= lockoutConfig.maxFailedAttempts) {
                    UserTable.update({ UserTable.id eq userId }) {
                        it[lockedUntil] = now.plus(lockoutConfig.lockoutDuration.toJavaDuration())
                        it[failedLoginAttempts] = 0
                    }
                } else {
                    UserTable.update({ UserTable.id eq userId }) {
                        it[failedLoginAttempts] = newCount
                    }
                }
                return@transaction Result.failure(UnauthorizedException("Invalid credentials"))
            }

            if (user[UserTable.failedLoginAttempts] > 0 || currentLock != null) {
                UserTable.update({ UserTable.id eq userId }) {
                    it[failedLoginAttempts] = 0
                    it[lockedUntil] = null
                }
            }

            Result.success(issueTokens(user))
        }
        return outcome.getOrThrow()
    }

    fun refresh(request: RefreshTokenRequest): AuthenticateResponse {
        val hash = sha256(request.refreshToken)
        val outcome: Result<AuthenticateResponse> = transaction {
            val tokenRow = RefreshTokenTable.selectAll()
                .where { RefreshTokenTable.tokenHash eq hash }
                .forUpdate()
                .singleOrNull()
                ?: return@transaction Result.failure(UnauthorizedException("Invalid refresh token"))

            val userId = tokenRow[RefreshTokenTable.userId].value
            val expiresAt = tokenRow[RefreshTokenTable.expiresAt]

            if (expiresAt.toInstant().isBefore(Instant.now())) {
                RefreshTokenTable.deleteWhere { RefreshTokenTable.userId eq userId }
                return@transaction Result.failure(UnauthorizedException("Refresh token expired"))
            }

            RefreshTokenTable.deleteWhere { RefreshTokenTable.tokenHash eq hash }

            val user = UserTable.selectAll()
                .where { UserTable.id eq userId }
                .singleOrNull()
                ?: return@transaction Result.failure(UnauthorizedException("Invalid refresh token"))

            Result.success(issueTokens(user))
        }
        return outcome.getOrThrow()
    }

    fun logout(refreshToken: String, userId: UUID) = transaction {
        val hash = sha256(refreshToken)
        val deleted = RefreshTokenTable.deleteWhere {
            (RefreshTokenTable.tokenHash eq hash) and (RefreshTokenTable.userId eq userId)
        }
        if (deleted == 0) {
            throw UnauthorizedException("Invalid refresh token")
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
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
package com.gvart.parleyroom.user.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.UnauthorizedException
import com.gvart.parleyroom.user.security.TelegramConfig
import com.gvart.parleyroom.user.transfer.TelegramLoginWidgetRequest
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.toJavaDuration

/**
 * Verifies a Telegram Login Widget payload per
 * https://core.telegram.org/widgets/login#checking-authorization
 *
 *   secret = SHA256(bot_token)
 *   hash   = HMAC_SHA256(data_check_string, secret)
 *
 * where `data_check_string` is all non-hash fields sorted alphabetically by key,
 * joined with '\n' as "key=value" pairs. Optional fields are omitted entirely
 * when absent (they must not appear in the data check string).
 *
 * Note: the scheme differs from Mini App initData — the Mini App derives its
 * secret as HMAC_SHA256("WebAppData", bot_token). Do not unify the two.
 */
class TelegramLoginWidgetVerifier(private val config: TelegramConfig) {

    fun verify(req: TelegramLoginWidgetRequest): VerifiedWidgetUser {
        if (config.botToken.isBlank()) {
            throw UnauthorizedException("Telegram bot is not configured on this server")
        }
        if (req.hash.isBlank()) {
            throw BadRequestException("widget payload is missing 'hash'")
        }

        val fields = sortedMapOf<String, String>().apply {
            put("id", req.id.toString())
            put("first_name", req.firstName)
            put("auth_date", req.authDate.toString())
            req.lastName?.let { put("last_name", it) }
            req.username?.let { put("username", it) }
            req.photoUrl?.let { put("photo_url", it) }
        }
        val dataCheckString = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }

        val secret = sha256(config.botToken.toByteArray())
        val expected = hmacSha256(secret, dataCheckString.toByteArray()).toHex()
        if (!constantTimeEquals(expected, req.hash.lowercase())) {
            throw UnauthorizedException("widget signature is invalid")
        }

        val authDate = Instant.ofEpochSecond(req.authDate)
        val maxAge = config.initDataMaxAge.toJavaDuration()
        if (authDate.plus(maxAge).isBefore(Instant.now())) {
            throw UnauthorizedException("widget payload has expired")
        }

        return VerifiedWidgetUser(
            telegramId = req.id,
            telegramUsername = req.username,
            authDate = authDate,
        )
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}

data class VerifiedWidgetUser(
    val telegramId: Long,
    val telegramUsername: String?,
    val authDate: Instant,
)

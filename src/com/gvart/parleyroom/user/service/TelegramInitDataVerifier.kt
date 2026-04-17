package com.gvart.parleyroom.user.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.UnauthorizedException
import com.gvart.parleyroom.user.security.TelegramConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.toJavaDuration

/**
 * Verifies Telegram Mini App initData per
 * https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app
 *
 *   secret = HMAC_SHA256("WebAppData", bot_token)
 *   hash   = HMAC_SHA256(data_check_string, secret)
 *
 * where `data_check_string` is all non-hash fields sorted alphabetically by key
 * and joined with '\n' as "key=value" pairs.
 */
class TelegramInitDataVerifier(private val config: TelegramConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    fun verify(initDataRaw: String): VerifiedInitData {
        if (config.botToken.isBlank()) {
            throw UnauthorizedException("Telegram bot is not configured on this server")
        }
        if (initDataRaw.isBlank()) {
            throw BadRequestException("initData is empty")
        }

        val pairs = parse(initDataRaw)
        val hash = pairs["hash"] ?: throw BadRequestException("initData is missing 'hash'")
        val dataCheckString = pairs
            .filterKeys { it != "hash" }
            .toSortedMap()
            .entries
            .joinToString("\n") { "${it.key}=${it.value}" }

        val secret = hmacSha256("WebAppData".toByteArray(), config.botToken.toByteArray())
        val expected = hmacSha256(secret, dataCheckString.toByteArray()).toHex()
        if (!constantTimeEquals(expected, hash)) {
            throw UnauthorizedException("initData signature is invalid")
        }

        val authDateSeconds = pairs["auth_date"]?.toLongOrNull()
            ?: throw BadRequestException("initData is missing or has invalid 'auth_date'")
        val authDate = Instant.ofEpochSecond(authDateSeconds)
        val maxAge = config.initDataMaxAge.toJavaDuration()
        if (authDate.plus(maxAge).isBefore(Instant.now())) {
            throw UnauthorizedException("initData has expired")
        }

        val userJson = pairs["user"]
            ?: throw BadRequestException("initData is missing 'user'")
        val user = runCatching { json.decodeFromString<TelegramUser>(userJson) }
            .getOrElse { throw BadRequestException("initData 'user' is not valid JSON: ${it.message}") }

        return VerifiedInitData(user = user, authDate = authDate)
    }

    private fun parse(raw: String): Map<String, String> =
        raw.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) throw BadRequestException("Malformed initData pair: $pair")
                val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8)
                val value = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
                key to value
            }

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

data class VerifiedInitData(
    val user: TelegramUser,
    val authDate: Instant,
)

@Serializable
data class TelegramUser(
    val id: Long,
    val first_name: String,
    val last_name: String? = null,
    val username: String? = null,
    val language_code: String? = null,
    val is_premium: Boolean? = null,
    val photo_url: String? = null,
)

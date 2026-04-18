package com.gvart.parleyroom.user

import com.gvart.parleyroom.user.transfer.TelegramLoginWidgetRequest
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Helper for building valid (or deliberately invalid) Telegram Login Widget
 * payloads signed with the test bot token. Mirrors the algorithm the production
 * TelegramLoginWidgetVerifier validates.
 *
 *   secret = SHA256(bot_token)
 *   hash   = HMAC_SHA256(data_check_string, secret)
 *
 * where data_check_string is snake-cased field names sorted alphabetically.
 */
object TelegramWidgetPayload {
    fun build(
        botToken: String,
        userId: Long,
        firstName: String = "Test",
        lastName: String? = null,
        username: String? = null,
        photoUrl: String? = null,
        authDateEpochSeconds: Long = System.currentTimeMillis() / 1000,
        overrideHash: String? = null,
    ): TelegramLoginWidgetRequest {
        val fields = sortedMapOf<String, String>().apply {
            put("id", userId.toString())
            put("first_name", firstName)
            put("auth_date", authDateEpochSeconds.toString())
            lastName?.let { put("last_name", it) }
            username?.let { put("username", it) }
            photoUrl?.let { put("photo_url", it) }
        }
        val dataCheckString = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }

        val secret = sha256(botToken.toByteArray())
        val hash = overrideHash ?: hmacSha256(secret, dataCheckString.toByteArray()).toHex()

        return TelegramLoginWidgetRequest(
            id = userId,
            firstName = firstName,
            lastName = lastName,
            username = username,
            photoUrl = photoUrl,
            authDate = authDateEpochSeconds,
            hash = hash,
        )
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

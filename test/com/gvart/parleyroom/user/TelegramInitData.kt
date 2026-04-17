package com.gvart.parleyroom.user

import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Helper for building valid (or deliberately invalid) Telegram Mini App initData
 * payloads signed with the test bot token. Mirrors the algorithm the production
 * TelegramInitDataVerifier validates.
 */
object TelegramInitData {
    fun build(
        botToken: String,
        userId: Long,
        firstName: String = "Test",
        lastName: String? = null,
        username: String? = null,
        authDateEpochSeconds: Long = System.currentTimeMillis() / 1000,
        overrideHash: String? = null,
        extraFields: Map<String, String> = emptyMap(),
    ): String {
        val userJson = buildString {
            append("{")
            append("\"id\":$userId")
            append(",\"first_name\":\"${escape(firstName)}\"")
            if (lastName != null) append(",\"last_name\":\"${escape(lastName)}\"")
            if (username != null) append(",\"username\":\"${escape(username)}\"")
            append("}")
        }
        val fields = sortedMapOf<String, String>().apply {
            put("user", userJson)
            put("auth_date", authDateEpochSeconds.toString())
            putAll(extraFields)
        }
        val dataCheckString = fields.entries.joinToString("\n") { "${it.key}=${it.value}" }

        val secret = hmacSha256("WebAppData".toByteArray(), botToken.toByteArray())
        val hash = overrideHash ?: hmacSha256(secret, dataCheckString.toByteArray()).toHex()

        return fields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
        } + "&hash=${URLEncoder.encode(hash, Charsets.UTF_8)}"
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

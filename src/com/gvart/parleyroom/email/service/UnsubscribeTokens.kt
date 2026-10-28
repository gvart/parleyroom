package com.gvart.parleyroom.email.service

import com.gvart.parleyroom.email.config.MailConfig
import com.gvart.parleyroom.email.transfer.EmailCategory
import com.gvart.parleyroom.user.security.JwtConfig
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless unsubscribe tokens: base64url(userId ‖ category ‖ HMAC-SHA256 truncated to 128 bits).
 * The key is derived from jwt.secret with a domain-separation label, so tokens never verify as anything
 * else. They do not expire; rotating JWT_SECRET invalidates them.
 */
class UnsubscribeTokens(jwtConfig: JwtConfig, private val config: MailConfig) {
    private val key = hmac(jwtConfig.secret.toByteArray(), DOMAIN.toByteArray())

    fun token(userId: UUID, category: EmailCategory): String {
        val payload = payload(userId, category)
        return ENCODER.encodeToString(payload + mac(payload))
    }

    /** The user and category of a valid token, null otherwise. */
    fun verify(token: String): Pair<UUID, EmailCategory>? {
        val bytes = runCatching { DECODER.decode(token) }.getOrNull() ?: return null
        if (bytes.size != PAYLOAD_SIZE + MAC_SIZE) return null
        val payload = bytes.copyOfRange(0, PAYLOAD_SIZE)
        if (!MessageDigest.isEqual(mac(payload), bytes.copyOfRange(PAYLOAD_SIZE, bytes.size))) return null
        val buffer = ByteBuffer.wrap(payload)
        val userId = UUID(buffer.long, buffer.long)
        val code = buffer.get()
        val category = CODES.entries.firstOrNull { it.value == code }?.key ?: return null
        return userId to category
    }

    /** The portal page that confirms the unsubscribe (footer link). */
    fun portalUrl(token: String): String = "${config.appBaseUrl}/unsubscribe?token=$token"

    /**
     * RFC 8058 one-click headers. Cloudflare rejects non-HTTPS List-Unsubscribe URIs, so none are added
     * while API_PUBLIC_URL is plain http (local dev).
     */
    fun headers(token: String): Map<String, String> =
        if (!config.apiPublicUrl.startsWith("https://")) emptyMap()
        else mapOf(
            "List-Unsubscribe" to "<${config.apiPublicUrl}/api/v1/email/unsubscribe?token=$token>",
            "List-Unsubscribe-Post" to "List-Unsubscribe=One-Click",
        )

    private fun payload(userId: UUID, category: EmailCategory): ByteArray =
        ByteBuffer.allocate(PAYLOAD_SIZE)
            .putLong(userId.mostSignificantBits)
            .putLong(userId.leastSignificantBits)
            .put(CODES.getValue(category))
            .array()

    private fun mac(payload: ByteArray) = hmac(key, payload).copyOf(MAC_SIZE)

    private companion object {
        const val DOMAIN = "parleyroom:email-unsubscribe:v1"
        const val PAYLOAD_SIZE = 17
        const val MAC_SIZE = 16
        /** Stable wire codes; never reuse one. */
        val CODES = mapOf(
            EmailCategory.LESSON_REMINDERS to 1.toByte(),
            EmailCategory.HOMEWORK_REMINDERS to 2.toByte(),
            EmailCategory.TEACHER_REMINDERS to 3.toByte(),
            EmailCategory.VOCAB_REMINDERS to 4.toByte(),
        )
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()

        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }
}

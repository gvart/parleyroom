package com.gvart.parleyroom.email.service

import com.gvart.parleyroom.email.config.MailConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.io.encoding.Base64

/**
 * Cloudflare Email Service REST API:
 * POST https://api.cloudflare.com/client/v4/accounts/{account_id}/email/sending/send
 * (https://developers.cloudflare.com/email-service/api/send-emails/rest-api/).
 */
class CloudflareEmailSender(
    private val config: MailConfig,
    private val client: HttpClient,
    private val baseUrl: String = "https://api.cloudflare.com/client/v4",
) : EmailSender {
    private val log = LoggerFactory.getLogger(CloudflareEmailSender::class.java)
    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }

    override suspend fun send(email: OutgoingEmail): Boolean = try {
        val response = client.post("$baseUrl/accounts/${config.cloudflareAccountId}/email/sending/send") {
            bearerAuth(config.cloudflareApiToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CloudflareSendRequest.serializer(), request(email)))
        }
        val body = response.bodyAsText()
        val result = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val success = result?.get("success")?.jsonPrimitive?.boolean == true
        val bounced = (result?.get("result") as? JsonObject)?.get("permanent_bounces")?.jsonArray?.isNotEmpty() == true
        if (response.status.isSuccess() && success && !bounced) true
        else {
            log.warn("Cloudflare email to {} failed: HTTP {} {}", email.to, response.status.value, body.take(500))
            false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Cloudflare email to {} failed", email.to, e)
        false
    }

    private fun request(email: OutgoingEmail) = CloudflareSendRequest(
        from = CloudflareAddress(config.from, config.brand.name),
        to = email.to,
        subject = email.subject,
        html = email.html,
        text = email.text,
        headers = email.headers.ifEmpty { null },
        attachments = email.inlineImages.map {
            CloudflareAttachment(
                content = Base64.encode(it.bytes),
                contentId = it.contentId,
                disposition = "inline",
                filename = it.filename,
                type = it.contentType,
            )
        }.ifEmpty { null },
    )
}

@Serializable
internal data class CloudflareSendRequest(
    val from: CloudflareAddress,
    val to: String,
    val subject: String,
    val html: String,
    val text: String,
    val headers: Map<String, String>? = null,
    val attachments: List<CloudflareAttachment>? = null,
)

@Serializable
internal data class CloudflareAddress(val address: String, val name: String)

@Serializable
internal data class CloudflareAttachment(
    val content: String,
    @SerialName("content_id") val contentId: String,
    val disposition: String,
    val filename: String,
    val type: String,
)

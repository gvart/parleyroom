package com.gvart.parleyroom.telegram.service

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Telegram Bot API sendMessage (https://core.telegram.org/bots/api#sendmessage). The bot token is part of
 * the URL, so neither the URL nor client exception messages (which may contain it) are ever logged.
 */
class BotApiTelegramSender(
    private val botToken: String,
    private val client: HttpClient,
    private val baseUrl: String = "https://api.telegram.org",
) : TelegramSender {
    private val log = LoggerFactory.getLogger(BotApiTelegramSender::class.java)
    private val json = Json { explicitNulls = false }

    override suspend fun send(message: OutgoingTelegramMessage): TelegramSendResult = try {
        val response = client.post("$baseUrl/bot$botToken/sendMessage") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SendMessageRequest.serializer(), request(message)))
        }
        when {
            response.status.isSuccess() -> TelegramSendResult.SENT
            else -> {
                log.warn("Telegram message to chat {} failed: HTTP {} {}", message.chatId, response.status.value, response.bodyAsText().take(500))
                if (response.status == HttpStatusCode.Forbidden) TelegramSendResult.FORBIDDEN else TelegramSendResult.FAILED
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Telegram message to chat {} failed: {}", message.chatId, e::class.simpleName)
        TelegramSendResult.FAILED
    }

    private fun request(message: OutgoingTelegramMessage) = SendMessageRequest(
        chatId = message.chatId,
        text = message.text,
        parseMode = "HTML",
        linkPreviewOptions = LinkPreviewOptions(isDisabled = true),
        replyMarkup = message.button?.let {
            InlineKeyboardMarkup(listOf(listOf(
                if (it.webApp) InlineKeyboardButton(it.text, webApp = WebAppInfo(it.url))
                else InlineKeyboardButton(it.text, url = it.url),
            )))
        },
    )
}

@Serializable
internal data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("parse_mode") val parseMode: String,
    @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null,
)

@Serializable
internal data class LinkPreviewOptions(@SerialName("is_disabled") val isDisabled: Boolean)

@Serializable
internal data class InlineKeyboardMarkup(@SerialName("inline_keyboard") val inlineKeyboard: List<List<InlineKeyboardButton>>)

@Serializable
internal data class InlineKeyboardButton(
    val text: String,
    val url: String? = null,
    @SerialName("web_app") val webApp: WebAppInfo? = null,
)

@Serializable
internal data class WebAppInfo(val url: String)

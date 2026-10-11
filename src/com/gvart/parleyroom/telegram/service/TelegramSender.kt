package com.gvart.parleyroom.telegram.service

/** An inline keyboard button: opens [url] as a Mini App when [webApp], else as a plain link. */
data class TelegramButton(val text: String, val url: String, val webApp: Boolean)

/** [text] is Telegram HTML (parse_mode = HTML), user content already escaped. */
data class OutgoingTelegramMessage(val chatId: Long, val text: String, val button: TelegramButton?)

enum class TelegramSendResult {
    SENT,
    /** Telegram answered 403: the user blocked the bot or never started it. */
    FORBIDDEN,
    FAILED,
}

fun interface TelegramSender {
    /** Never throws: a failure is logged and reported as [TelegramSendResult.FAILED] / [TelegramSendResult.FORBIDDEN]. */
    suspend fun send(message: OutgoingTelegramMessage): TelegramSendResult
}

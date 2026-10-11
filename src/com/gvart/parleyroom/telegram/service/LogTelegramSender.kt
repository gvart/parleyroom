package com.gvart.parleyroom.telegram.service

import org.slf4j.LoggerFactory

/** TELEGRAM_PROVIDER=log: nothing leaves the server; the text is logged at debug. */
class LogTelegramSender : TelegramSender {
    private val log = LoggerFactory.getLogger(LogTelegramSender::class.java)

    override suspend fun send(message: OutgoingTelegramMessage): TelegramSendResult {
        log.info("Telegram message (not sent, TELEGRAM_PROVIDER=log) to chat {}", message.chatId)
        log.debug("Telegram message to chat {}:\n{}\n[{}]({})", message.chatId, message.text, message.button?.text, message.button?.url)
        return TelegramSendResult.SENT
    }
}

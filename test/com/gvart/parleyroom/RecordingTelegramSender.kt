package com.gvart.parleyroom

import com.gvart.parleyroom.telegram.service.OutgoingTelegramMessage
import com.gvart.parleyroom.telegram.service.TelegramSendResult
import com.gvart.parleyroom.telegram.service.TelegramSender
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory [TelegramSender] for tests; set [result] to simulate a 403 or another failure. */
class RecordingTelegramSender(var result: TelegramSendResult = TelegramSendResult.SENT) : TelegramSender {
    val sent: MutableList<OutgoingTelegramMessage> = CopyOnWriteArrayList()

    override suspend fun send(message: OutgoingTelegramMessage): TelegramSendResult {
        sent += message
        return result
    }
}

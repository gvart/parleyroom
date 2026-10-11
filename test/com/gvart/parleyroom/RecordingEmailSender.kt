package com.gvart.parleyroom

import com.gvart.parleyroom.email.service.EmailSender
import com.gvart.parleyroom.email.service.OutgoingEmail
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory [EmailSender] for tests; [succeed] = false simulates a provider failure. */
class RecordingEmailSender(var succeed: Boolean = true) : EmailSender {
    val sent: MutableList<OutgoingEmail> = CopyOnWriteArrayList()

    override suspend fun send(email: OutgoingEmail): Boolean {
        sent += email
        return succeed
    }
}

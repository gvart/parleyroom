package com.gvart.parleyroom.email.service

import org.slf4j.LoggerFactory

/** MAIL_PROVIDER=log: nothing leaves the server; the body is logged at debug. */
class LogEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(LogEmailSender::class.java)

    override suspend fun send(email: OutgoingEmail): Boolean {
        log.info("Email (not sent, MAIL_PROVIDER=log) to {}: {}", email.to, email.subject)
        log.debug("Email body to {}:\n{}", email.to, email.text)
        return true
    }
}

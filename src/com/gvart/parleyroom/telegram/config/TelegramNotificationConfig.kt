package com.gvart.parleyroom.telegram.config

data class TelegramNotificationConfig(
    /** bot | log */
    val provider: String,
    /** Public Mini App origin; empty = reminder buttons link to the portal ([appBaseUrl]) instead. */
    val miniAppBaseUrl: String,
    /** Public portal origin (MailConfig.appBaseUrl). */
    val appBaseUrl: String,
) {
    companion object {
        const val PROVIDER_BOT = "bot"
        const val PROVIDER_LOG = "log"
    }
}

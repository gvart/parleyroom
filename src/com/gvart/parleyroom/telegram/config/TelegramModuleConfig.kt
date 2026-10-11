package com.gvart.parleyroom.telegram.config

import com.gvart.parleyroom.email.config.MailConfig
import com.gvart.parleyroom.telegram.routing.configureTelegramRouting
import com.gvart.parleyroom.telegram.service.BotApiTelegramSender
import com.gvart.parleyroom.telegram.service.LogTelegramSender
import com.gvart.parleyroom.telegram.service.TelegramNotifier
import com.gvart.parleyroom.telegram.service.TelegramSender
import com.gvart.parleyroom.telegram.template.TelegramTemplates
import com.gvart.parleyroom.user.security.TelegramConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.di.dependencies

/** After the email module (MailConfig). [sender] overrides TELEGRAM_PROVIDER (tests pass a recording sender). */
fun Application.configureTelegramModule(sender: TelegramSender? = null) {
    val config = environment.config
    val mailConfig: MailConfig by dependencies
    val telegramConfig: TelegramConfig by dependencies

    val notificationConfig = TelegramNotificationConfig(
        provider = (config.propertyOrNull("telegram.provider")?.getString() ?: TelegramNotificationConfig.PROVIDER_LOG).lowercase(),
        miniAppBaseUrl = (config.propertyOrNull("telegram.miniapp_base_url")?.getString() ?: "").trimEnd('/'),
        appBaseUrl = mailConfig.appBaseUrl,
    )

    val telegramSender = sender ?: when (notificationConfig.provider) {
        TelegramNotificationConfig.PROVIDER_BOT -> {
            if (telegramConfig.botToken.isBlank())
                environment.log.warn("TELEGRAM_PROVIDER=bot but TELEGRAM_BOT_TOKEN is empty: every Telegram message will fail")
            val client = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            }
            monitor.subscribe(ApplicationStopping) { client.close() }
            BotApiTelegramSender(telegramConfig.botToken, client)
        }
        else -> {
            if (notificationConfig.provider != TelegramNotificationConfig.PROVIDER_LOG)
                environment.log.warn("Unknown TELEGRAM_PROVIDER '${notificationConfig.provider}', falling back to log")
            LogTelegramSender()
        }
    }
    environment.log.info(
        "Telegram provider: ${if (sender != null) "custom" else notificationConfig.provider}, " +
            "buttons open ${notificationConfig.miniAppBaseUrl.ifEmpty { "the portal" }}",
    )

    dependencies {
        provide { notificationConfig }
        provide { telegramSender }
        provide { TelegramTemplates(mailConfig.brand) }
        provide(TelegramNotifier::class)
    }

    configureTelegramRouting()
}

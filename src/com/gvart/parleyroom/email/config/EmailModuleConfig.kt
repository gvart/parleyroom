package com.gvart.parleyroom.email.config

import com.gvart.parleyroom.email.routing.configureEmailRouting
import com.gvart.parleyroom.email.service.CloudflareEmailSender
import com.gvart.parleyroom.email.service.EmailPreferenceService
import com.gvart.parleyroom.email.service.EmailSender
import com.gvart.parleyroom.email.service.LogEmailSender
import com.gvart.parleyroom.email.service.NotificationMailer
import com.gvart.parleyroom.email.service.UnsubscribeTokens
import com.gvart.parleyroom.email.template.EmailTemplates
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.di.dependencies

/** [sender] overrides MAIL_PROVIDER (tests pass a recording sender). */
fun Application.configureEmailModule(sender: EmailSender? = null) {
    val config = environment.config
    fun value(path: String, default: String) = config.propertyOrNull(path)?.getString() ?: default

    val mailConfig = MailConfig(
        provider = value("mail.provider", MailConfig.PROVIDER_LOG).lowercase(),
        cloudflareAccountId = value("mail.cloudflare.account_id", ""),
        cloudflareApiToken = value("mail.cloudflare.api_token", ""),
        from = value("mail.from", "noreply@example.com"),
        brandName = value("mail.brand_name", "Parley|room"),
        appBaseUrl = value("mail.app_base_url", "http://localhost:5173").trimEnd('/'),
        apiPublicUrl = value("mail.api_public_url", "http://localhost:8080").trimEnd('/'),
    )

    val emailSender = sender ?: when (mailConfig.provider) {
        MailConfig.PROVIDER_CLOUDFLARE -> {
            if (mailConfig.cloudflareAccountId.isBlank() || mailConfig.cloudflareApiToken.isBlank())
                environment.log.warn("MAIL_PROVIDER=cloudflare but CLOUDFLARE_ACCOUNT_ID or CLOUDFLARE_EMAIL_API_TOKEN is empty: every email will fail")
            if (!mailConfig.apiPublicUrl.startsWith("https://"))
                environment.log.warn("API_PUBLIC_URL is not https: reminder emails go out without List-Unsubscribe headers")
            val client = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 15_000 }
            }
            monitor.subscribe(ApplicationStopping) { client.close() }
            CloudflareEmailSender(mailConfig, client)
        }
        else -> {
            if (mailConfig.provider != MailConfig.PROVIDER_LOG)
                environment.log.warn("Unknown MAIL_PROVIDER '${mailConfig.provider}', falling back to log")
            LogEmailSender()
        }
    }
    environment.log.info("Mail provider: ${if (sender != null) "custom" else mailConfig.provider}, from ${mailConfig.from}")

    dependencies {
        provide { mailConfig }
        provide { emailSender }
        provide { EmailTemplates(mailConfig.brand) }
        provide(UnsubscribeTokens::class)
        provide(EmailPreferenceService::class)
        provide(NotificationMailer::class)
    }

    configureEmailRouting()
}

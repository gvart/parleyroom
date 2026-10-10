package com.gvart.parleyroom.email.config

data class MailConfig(
    /** cloudflare | log */
    val provider: String,
    val cloudflareAccountId: String,
    val cloudflareApiToken: String,
    val from: String,
    /** May contain one `|` where the accent half of the wordmark starts (like the portal's VITE_BRAND_NAME). */
    val brandName: String,
    /** Public portal origin, for links in emails. */
    val appBaseUrl: String,
    /** Public backend origin, for the one-click List-Unsubscribe endpoint. */
    val apiPublicUrl: String,
) {
    val brand: Brand get() = Brand.parse(brandName)

    companion object {
        const val PROVIDER_CLOUDFLARE = "cloudflare"
        const val PROVIDER_LOG = "log"
    }
}

data class Brand(val head: String, val accent: String) {
    /** Plain product name: sender display name and copy. */
    val name: String get() = head + accent

    companion object {
        fun parse(raw: String): Brand {
            val parts = raw.split('|', limit = 2)
            return Brand(parts[0], parts.getOrElse(1) { "" }.replace("|", ""))
        }
    }
}

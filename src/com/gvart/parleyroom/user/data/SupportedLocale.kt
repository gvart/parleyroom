package com.gvart.parleyroom.user.data

import com.gvart.parleyroom.common.transfer.exception.BadRequestException

/** Interface languages the clients ship translations for. Add a tag here to enable a new one (e.g. "ru"). */
val SUPPORTED_LOCALES = setOf("en", "de")

fun requireSupportedLocale(locale: String) {
    if (locale !in SUPPORTED_LOCALES) {
        throw BadRequestException(
            "Locale '$locale' is not supported. Supported: ${SUPPORTED_LOCALES.joinToString()}",
            code = "UNSUPPORTED_LOCALE",
        )
    }
}

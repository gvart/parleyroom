package com.gvart.parleyroom.user.data

import com.gvart.parleyroom.common.transfer.exception.BadRequestException

/** Interface languages the clients ship translations for. Add a tag here to enable a new one. */
val SUPPORTED_LOCALES = setOf("ru", "de", "en")

const val DEFAULT_LOCALE = "ru"

/** Languages a student can get translations and explanations in (see VocabDisplay.TRANSLATION_LANGUAGES). */
val NATIVE_LANGUAGES = setOf("ru", "uk", "en")

const val DEFAULT_NATIVE_LANGUAGE = "ru"

fun requireSupportedLocale(locale: String) {
    if (locale !in SUPPORTED_LOCALES) {
        throw BadRequestException(
            "Locale '$locale' is not supported. Supported: ${SUPPORTED_LOCALES.joinToString()}",
            code = "UNSUPPORTED_LOCALE",
        )
    }
}

fun requireSupportedNativeLanguage(language: String) {
    if (language !in NATIVE_LANGUAGES) {
        throw BadRequestException(
            "Native language '$language' is not supported. Supported: ${NATIVE_LANGUAGES.joinToString()}",
            code = "UNSUPPORTED_NATIVE_LANGUAGE",
        )
    }
}

package com.gvart.parleyroom.vocabulary.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.user.data.DEFAULT_NATIVE_LANGUAGE
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting

/**
 * Vocab display rules. Translation languages are an open set: add a code to
 * [TRANSLATION_LANGUAGES] to support a new language (translations are stored as JSONB).
 */
object VocabDisplay {
    val TRANSLATION_LANGUAGES = setOf("ru", "uk", "en")
    const val DE_EXPLANATION = "de_explanation"
    val DISPLAY_FIELDS = TRANSLATION_LANGUAGES + DE_EXPLANATION

    /** A1–A2 (or unknown level): the student's native-language translation (ru when unset). B1+: German explanation with a reveal toggle. */
    fun defaultFor(level: LanguageLevel?, nativeLanguage: String?): VocabDisplaySetting = when (level) {
        null, LanguageLevel.A1, LanguageLevel.A2 ->
            VocabDisplaySetting(listOf(nativeLanguage ?: DEFAULT_NATIVE_LANGUAGE), allowTranslationToggle = false)
        else -> VocabDisplaySetting(listOf(DE_EXPLANATION), allowTranslationToggle = true)
    }

    fun requireSupportedLanguages(translations: Map<String, String>) {
        val unsupported = translations.keys - TRANSLATION_LANGUAGES
        if (unsupported.isNotEmpty())
            throw BadRequestException(
                "Unsupported translation languages: ${unsupported.joinToString()}",
                code = "VOCAB_LANGUAGE_UNSUPPORTED",
            )
    }

    fun requireSupportedFields(setting: VocabDisplaySetting) {
        val unsupported = setting.fields.toSet() - DISPLAY_FIELDS
        if (unsupported.isNotEmpty())
            throw BadRequestException(
                "Unsupported display fields: ${unsupported.joinToString()}",
                code = "VOCAB_DISPLAY_FIELD_UNSUPPORTED",
            )
    }

    fun of(fields: List<String>?, allowToggle: Boolean?): VocabDisplaySetting? =
        fields?.let { VocabDisplaySetting(it, allowToggle ?: false) }
}

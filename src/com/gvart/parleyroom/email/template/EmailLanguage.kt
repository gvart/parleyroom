package com.gvart.parleyroom.email.template

import com.gvart.parleyroom.user.data.DEFAULT_LOCALE
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ResultRow
import java.util.Locale

/** Languages emails are written in: the UI locales (ru, de, en) plus the students' native uk. */
enum class EmailLanguage(val tag: String) {
    RU("ru"), UK("uk"), EN("en"), DE("de");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun of(tag: String?): EmailLanguage? = entries.firstOrNull { it.tag == tag }
    }
}

/** Students read emails in their native language (ru | uk | en), everyone else in their UI locale. */
fun emailLanguageFor(role: UserRole, locale: String, nativeLanguage: String?): EmailLanguage {
    val preferred = if (role == UserRole.STUDENT) EmailLanguage.of(nativeLanguage) else null
    return preferred ?: EmailLanguage.of(locale) ?: EmailLanguage.of(DEFAULT_LOCALE)!!
}

/** [emailLanguageFor] for a users row. */
fun emailLanguageFor(user: ResultRow): EmailLanguage =
    emailLanguageFor(user[UserTable.role], user[UserTable.locale], user[UserTable.nativeLanguage])

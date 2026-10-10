package com.gvart.parleyroom.email.template

import com.gvart.parleyroom.email.config.Brand
import com.gvart.parleyroom.email.template.EmailLanguage.DE
import com.gvart.parleyroom.email.template.EmailLanguage.EN
import com.gvart.parleyroom.email.template.EmailLanguage.RU
import com.gvart.parleyroom.email.template.EmailLanguage.UK
import com.gvart.parleyroom.user.data.UserRole
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class LessonReminderVariant { H24, H1 }

/** The email copy in ru / uk / en / de. Every template returns subject, HTML and plain text. */
class EmailTemplates(private val brand: Brand) {

    fun invite(
        lang: EmailLanguage,
        inviterName: String,
        role: UserRole,
        registerUrl: String,
        expiresAt: OffsetDateTime,
        zone: ZoneId = ZoneOffset.UTC,
    ): RenderedEmail {
        val b = brand.name
        val until = date(lang, expiresAt, zone)
        val content = when (lang) {
            EN -> EmailContent(
                lang, subject = "$inviterName invited you to $b", preheader = "Create your account — the link is valid until $until.",
                robbi = RobbiPose.WAVE, heading = "You're invited!",
                paragraphs = listOf(when (role) {
                    UserRole.STUDENT -> "$inviterName invited you to learn German on $b. Lessons, homework and your vocabulary — all in one place."
                    UserRole.TEACHER -> "$inviterName invited you to teach on $b. Lessons, homework and your students — all in one place."
                    UserRole.ADMIN -> "$inviterName invited you to help run $b as an admin."
                }),
                details = listOf("Your role" to roleName(lang, role), "Link valid until" to until),
                ctaLabel = "Create account", ctaUrl = registerUrl, linkHint = linkHint(lang),
                note = "Not expecting this invite? Just ignore this email.",
                signoff = signoff(lang),
            )
            RU -> EmailContent(
                lang, subject = "$inviterName приглашает вас в $b", preheader = "Создайте аккаунт — ссылка действует до $until.",
                robbi = RobbiPose.WAVE, heading = "Вас пригласили!",
                paragraphs = listOf(when (role) {
                    UserRole.STUDENT -> "$inviterName приглашает вас учить немецкий в $b. Уроки, домашние задания и ваш словарь — всё в одном месте."
                    UserRole.TEACHER -> "$inviterName приглашает вас преподавать в $b. Уроки, домашние задания и ваши ученики — всё в одном месте."
                    UserRole.ADMIN -> "$inviterName приглашает вас в команду $b в роли администратора."
                }),
                details = listOf("Ваша роль" to roleName(lang, role), "Ссылка действует до" to until),
                ctaLabel = "Создать аккаунт", ctaUrl = registerUrl, linkHint = linkHint(lang),
                note = "Не ждали приглашения? Просто проигнорируйте это письмо.",
                signoff = signoff(lang),
            )
            UK -> EmailContent(
                lang, subject = "$inviterName запрошує вас до $b", preheader = "Створіть акаунт — посилання дійсне до $until.",
                robbi = RobbiPose.WAVE, heading = "Вас запросили!",
                paragraphs = listOf(when (role) {
                    UserRole.STUDENT -> "$inviterName запрошує вас вивчати німецьку в $b. Уроки, домашні завдання та ваш словник — усе в одному місці."
                    UserRole.TEACHER -> "$inviterName запрошує вас викладати в $b. Уроки, домашні завдання та ваші учні — усе в одному місці."
                    UserRole.ADMIN -> "$inviterName запрошує вас до команди $b як адміністратора."
                }),
                details = listOf("Ваша роль" to roleName(lang, role), "Посилання дійсне до" to until),
                ctaLabel = "Створити акаунт", ctaUrl = registerUrl, linkHint = linkHint(lang),
                note = "Не чекали на запрошення? Просто проігноруйте цей лист.",
                signoff = signoff(lang),
            )
            DE -> EmailContent(
                lang, subject = "$inviterName hat dich zu $b eingeladen", preheader = "Erstelle dein Konto – der Link gilt bis $until.",
                robbi = RobbiPose.WAVE, heading = "Du bist eingeladen!",
                paragraphs = listOf(when (role) {
                    UserRole.STUDENT -> "$inviterName hat dich eingeladen, auf $b Deutsch zu lernen. Unterricht, Hausaufgaben und dein Wortschatz – alles an einem Ort."
                    UserRole.TEACHER -> "$inviterName hat dich eingeladen, auf $b zu unterrichten. Unterricht, Hausaufgaben und deine Lernenden – alles an einem Ort."
                    UserRole.ADMIN -> "$inviterName hat dich eingeladen, $b als Admin mitzugestalten."
                }),
                details = listOf("Deine Rolle" to roleName(lang, role), "Link gültig bis" to until),
                ctaLabel = "Konto erstellen", ctaUrl = registerUrl, linkHint = linkHint(lang),
                note = "Du hast keine Einladung erwartet? Dann ignoriere diese E-Mail einfach.",
                signoff = signoff(lang),
            )
        }
        return render(content)
    }

    /**
     * [recipientRole] picks the counterpart label: a student sees the teacher, a teacher the attendees
     * ([counterpartName] may then list several students or a club).
     */
    fun lessonReminder(
        lang: EmailLanguage,
        variant: LessonReminderVariant,
        lessonTitle: String,
        startsAt: OffsetDateTime,
        zone: ZoneId,
        recipientRole: UserRole,
        counterpartName: String?,
        lessonUrl: String,
        footer: ReminderFooter,
    ): RenderedEmail {
        val time = time(startsAt, zone)
        val soon = variant == LessonReminderVariant.H1
        val student = recipientRole == UserRole.STUDENT
        val content = when (lang) {
            EN -> EmailContent(
                lang,
                subject = if (soon) "$lessonTitle starts in 1 hour" else "Reminder: $lessonTitle tomorrow at $time",
                preheader = if (soon) "Starts at $time — see you there." else "Tomorrow at $time. All the details inside.",
                robbi = if (soon) RobbiPose.CELEBRATE else RobbiPose.IDLE,
                heading = if (soon) "Starting in an hour!" else "See you tomorrow!",
                paragraphs = listOf(
                    if (soon) "Your lesson starts in about an hour — time to grab a tea and get comfy."
                    else "Quick reminder: you have a lesson tomorrow. All the details are below.",
                ),
                details = listOfNotNull(
                    "Lesson" to lessonTitle,
                    "When" to dateTime(lang, startsAt, zone),
                    counterpartName?.let { (if (student) "Teacher" else "Attendees") to it },
                ),
                ctaLabel = "View lesson", ctaUrl = lessonUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = "You're getting this because lesson reminders are on in your ${brand.name} account.",
                footerLinks = footerLinks(lang, footer),
            )
            RU -> EmailContent(
                lang,
                subject = if (soon) "«$lessonTitle» начнётся через час" else "Напоминание: «$lessonTitle» завтра в $time",
                preheader = if (soon) "Начало в $time — до встречи!" else "Завтра в $time. Подробности внутри.",
                robbi = if (soon) RobbiPose.CELEBRATE else RobbiPose.IDLE,
                heading = if (soon) "Через час — урок!" else "До встречи завтра!",
                paragraphs = listOf(
                    if (soon) "Урок начнётся примерно через час — самое время налить чай и устроиться поудобнее."
                    else "Небольшое напоминание: завтра у вас урок. Все подробности ниже.",
                ),
                details = listOfNotNull(
                    "Урок" to lessonTitle,
                    "Когда" to dateTime(lang, startsAt, zone),
                    counterpartName?.let { (if (student) "Преподаватель" else "Участники") to it },
                ),
                ctaLabel = "Открыть урок", ctaUrl = lessonUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = "Вы получили это письмо, потому что в вашем аккаунте ${brand.name} включены напоминания об уроках.",
                footerLinks = footerLinks(lang, footer),
            )
            UK -> EmailContent(
                lang,
                subject = if (soon) "«$lessonTitle» почнеться за годину" else "Нагадування: «$lessonTitle» завтра о $time",
                preheader = if (soon) "Початок о $time — до зустрічі!" else "Завтра о $time. Деталі всередині.",
                robbi = if (soon) RobbiPose.CELEBRATE else RobbiPose.IDLE,
                heading = if (soon) "За годину — урок!" else "До зустрічі завтра!",
                paragraphs = listOf(
                    if (soon) "Урок почнеться приблизно за годину — саме час заварити чай і влаштуватися зручніше."
                    else "Невелике нагадування: завтра у вас урок. Усі деталі нижче.",
                ),
                details = listOfNotNull(
                    "Урок" to lessonTitle,
                    "Коли" to dateTime(lang, startsAt, zone),
                    counterpartName?.let { (if (student) "Викладач" else "Учасники") to it },
                ),
                ctaLabel = "Відкрити урок", ctaUrl = lessonUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = "Ви отримали цей лист, бо у вашому акаунті ${brand.name} увімкнено нагадування про уроки.",
                footerLinks = footerLinks(lang, footer),
            )
            DE -> EmailContent(
                lang,
                subject = if (soon) "„$lessonTitle“ beginnt in einer Stunde" else "Erinnerung: „$lessonTitle“ morgen um $time",
                preheader = if (soon) "Beginn um $time – bis gleich!" else "Morgen um $time. Alle Details in der E-Mail.",
                robbi = if (soon) RobbiPose.CELEBRATE else RobbiPose.IDLE,
                heading = if (soon) "In einer Stunde geht’s los!" else "Bis morgen!",
                paragraphs = listOf(
                    if (soon) "Bald ist es so weit – Zeit für einen Tee und einen gemütlichen Platz."
                    else "Kurze Erinnerung: Morgen hast du eine Stunde. Alle Details findest du unten.",
                ),
                details = listOfNotNull(
                    "Stunde" to lessonTitle,
                    "Wann" to dateTime(lang, startsAt, zone),
                    counterpartName?.let { (if (student) "Lehrkraft" else "Teilnehmende") to it },
                ),
                ctaLabel = "Zur Stunde", ctaUrl = lessonUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = "Du bekommst diese E-Mail, weil in deinem ${brand.name}-Konto Erinnerungen an Stunden aktiviert sind.",
                footerLinks = footerLinks(lang, footer),
            )
        }
        return render(content)
    }

    fun homeworkDueTomorrow(
        lang: EmailLanguage,
        assignmentTitle: String,
        dueAt: OffsetDateTime,
        zone: ZoneId,
        homeworkUrl: String,
        footer: ReminderFooter,
    ): RenderedEmail {
        val due = dateTime(lang, dueAt, zone)
        val t = assignmentTitle
        val content = when (lang) {
            EN -> EmailContent(
                lang, subject = "Homework due tomorrow: $t", preheader = "A few minutes today and you're done.",
                robbi = RobbiPose.IDLE, heading = "Due tomorrow",
                paragraphs = listOf("“$t” is due tomorrow. A few minutes today and you're done — Robbi believes in you!"),
                details = listOf("Assignment" to t, "Due" to due),
                ctaLabel = "Open homework", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
            RU -> EmailContent(
                lang, subject = "Домашнее задание на завтра: $t", preheader = "Пара минут сегодня — и готово.",
                robbi = RobbiPose.IDLE, heading = "Срок сдачи — завтра",
                paragraphs = listOf("Задание «$t» нужно сдать завтра. Пара минут сегодня — и готово. Робби в вас верит!"),
                details = listOf("Задание" to t, "Срок сдачи" to due),
                ctaLabel = "Открыть задание", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
            UK -> EmailContent(
                lang, subject = "Домашнє завдання на завтра: $t", preheader = "Кілька хвилин сьогодні — і готово.",
                robbi = RobbiPose.IDLE, heading = "Термін здачі — завтра",
                paragraphs = listOf("Завдання «$t» треба здати завтра. Кілька хвилин сьогодні — і готово. Роббі вірить у вас!"),
                details = listOf("Завдання" to t, "Термін здачі" to due),
                ctaLabel = "Відкрити завдання", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
            DE -> EmailContent(
                lang, subject = "Hausaufgabe bis morgen: $t", preheader = "Ein paar Minuten heute, und du bist fertig.",
                robbi = RobbiPose.IDLE, heading = "Morgen ist Abgabe",
                paragraphs = listOf("„$t“ ist morgen fällig. Ein paar Minuten heute, und du bist fertig – Robbi glaubt an dich!"),
                details = listOf("Aufgabe" to t, "Fällig" to due),
                ctaLabel = "Zur Hausaufgabe", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
        }
        return render(content)
    }

    fun homeworkOverdue(
        lang: EmailLanguage,
        assignmentTitle: String,
        dueAt: OffsetDateTime,
        zone: ZoneId,
        homeworkUrl: String,
        footer: ReminderFooter,
    ): RenderedEmail {
        val due = dateTime(lang, dueAt, zone)
        val t = assignmentTitle
        val content = when (lang) {
            EN -> EmailContent(
                lang, subject = "Overdue homework: $t", preheader = "No stress — you can still hand it in.",
                robbi = RobbiPose.IDLE, heading = "This one slipped by",
                paragraphs = listOf("“$t” is past its due date. No stress — you can still hand it in, and your teacher will be happy to see it."),
                details = listOf("Assignment" to t, "Was due" to due),
                ctaLabel = "Finish homework", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
            RU -> EmailContent(
                lang, subject = "Просроченное задание: $t", preheader = "Ничего страшного — его ещё можно сдать.",
                robbi = RobbiPose.IDLE, heading = "Задание ждёт вас",
                paragraphs = listOf("Срок сдачи задания «$t» прошёл. Ничего страшного — его ещё можно сдать, и преподаватель будет рад его увидеть."),
                details = listOf("Задание" to t, "Срок сдачи" to due),
                ctaLabel = "Доделать задание", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
            UK -> EmailContent(
                lang, subject = "Прострочене завдання: $t", preheader = "Нічого страшного — його ще можна здати.",
                robbi = RobbiPose.IDLE, heading = "Завдання чекає на вас",
                paragraphs = listOf("Термін здачі завдання «$t» минув. Нічого страшного — його ще можна здати, і викладач буде радий його побачити."),
                details = listOf("Завдання" to t, "Термін здачі" to due),
                ctaLabel = "Доробити завдання", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
            DE -> EmailContent(
                lang, subject = "Überfällige Hausaufgabe: $t", preheader = "Kein Stress – du kannst sie noch abgeben.",
                robbi = RobbiPose.IDLE, heading = "Da ist etwas liegen geblieben",
                paragraphs = listOf("„$t“ ist überfällig. Kein Stress – du kannst sie noch abgeben, und deine Lehrkraft freut sich darüber."),
                details = listOf("Aufgabe" to t, "War fällig" to due),
                ctaLabel = "Hausaufgabe abschließen", ctaUrl = homeworkUrl, linkHint = linkHint(lang),
                signoff = signoff(lang),
                footerReason = homeworkReason(lang), footerLinks = footerLinks(lang, footer),
            )
        }
        return render(content)
    }

    private fun render(content: EmailContent) =
        RenderedEmail(content.subject, EmailLayout.html(content, brand), EmailLayout.text(content), content.robbi)

    private fun roleName(lang: EmailLanguage, role: UserRole): String = when (lang) {
        EN -> when (role) { UserRole.STUDENT -> "Student"; UserRole.TEACHER -> "Teacher"; UserRole.ADMIN -> "Admin" }
        RU -> when (role) { UserRole.STUDENT -> "Ученик"; UserRole.TEACHER -> "Преподаватель"; UserRole.ADMIN -> "Администратор" }
        UK -> when (role) { UserRole.STUDENT -> "Учень"; UserRole.TEACHER -> "Викладач"; UserRole.ADMIN -> "Адміністратор" }
        DE -> when (role) { UserRole.STUDENT -> "Lernende"; UserRole.TEACHER -> "Lehrkraft"; UserRole.ADMIN -> "Admin" }
    }

    private fun linkHint(lang: EmailLanguage): String = when (lang) {
        EN -> "Button not working? Copy this link into your browser:"
        RU -> "Кнопка не работает? Скопируйте ссылку в браузер:"
        UK -> "Кнопка не працює? Скопіюйте посилання в браузер:"
        DE -> "Der Button funktioniert nicht? Kopiere diesen Link in deinen Browser:"
    }

    private fun signoff(lang: EmailLanguage): List<String> = when (lang) {
        EN -> listOf("See you soon,", "Robbi & the ${brand.name} team")
        RU -> listOf("До встречи!", "Робби и команда ${brand.name}")
        UK -> listOf("До зустрічі!", "Роббі та команда ${brand.name}")
        DE -> listOf("Bis bald!", "Robbi & das ${brand.name}-Team")
    }

    private fun homeworkReason(lang: EmailLanguage): String = when (lang) {
        EN -> "You're getting this because homework reminders are on in your ${brand.name} account."
        RU -> "Вы получили это письмо, потому что в вашем аккаунте ${brand.name} включены напоминания о домашних заданиях."
        UK -> "Ви отримали цей лист, бо у вашому акаунті ${brand.name} увімкнено нагадування про домашні завдання."
        DE -> "Du bekommst diese E-Mail, weil in deinem ${brand.name}-Konto Erinnerungen an Hausaufgaben aktiviert sind."
    }

    private fun footerLinks(lang: EmailLanguage, footer: ReminderFooter): List<Pair<String, String>> = when (lang) {
        EN -> listOf("Email settings" to footer.settingsUrl, "Unsubscribe" to footer.unsubscribeUrl)
        RU -> listOf("Настройки писем" to footer.settingsUrl, "Отписаться" to footer.unsubscribeUrl)
        UK -> listOf("Налаштування листів" to footer.settingsUrl, "Відписатися" to footer.unsubscribeUrl)
        DE -> listOf("E-Mail-Einstellungen" to footer.settingsUrl, "Abmelden" to footer.unsubscribeUrl)
    }

    private fun time(at: OffsetDateTime, zone: ZoneId): String =
        at.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

    /** e.g. "Wednesday, 15 October · 18:00" + a second line with the zone, in the recipient's language and zone. */
    private fun dateTime(lang: EmailLanguage, at: OffsetDateTime, zone: ZoneId): String {
        val pattern = if (lang == DE) "EEEE, d. MMMM · HH:mm" else "EEEE, d MMMM · HH:mm"
        val formatted = at.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern(pattern, lang.locale))
        return "${formatted.replaceFirstChar { it.titlecase(lang.locale) }}\n${zone.id.replace('_', ' ')}"
    }

    private fun date(lang: EmailLanguage, at: OffsetDateTime, zone: ZoneId): String {
        val pattern = if (lang == DE) "d. MMMM yyyy" else "d MMMM yyyy"
        return at.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern(pattern, lang.locale))
    }
}

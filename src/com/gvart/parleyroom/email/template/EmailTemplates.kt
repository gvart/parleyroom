package com.gvart.parleyroom.email.template

import com.gvart.parleyroom.email.config.Brand
import com.gvart.parleyroom.email.template.EmailLanguage.DE
import com.gvart.parleyroom.email.template.EmailLanguage.EN
import com.gvart.parleyroom.email.template.EmailLanguage.RU
import com.gvart.parleyroom.email.template.EmailLanguage.UK
import com.gvart.parleyroom.reminder.transfer.AttentionReasonType
import com.gvart.parleyroom.reminder.transfer.TeacherDigestLine
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

    /**
     * To the teacher hours after a 1:1: what is still open ([wrapUp]: check off what was covered, [homework]: none
     * assigned). Only the student's first name; no lesson notes.
     */
    fun teacherFollowUp(
        lang: EmailLanguage,
        studentFirstName: String,
        lessonTitle: String,
        startsAt: OffsetDateTime,
        zone: ZoneId,
        wrapUp: Boolean,
        homework: Boolean,
        url: String,
        footer: ReminderFooter,
    ): RenderedEmail {
        val n = studentFirstName
        val whenText = dateTime(lang, startsAt, zone)
        val content = when (lang) {
            EN -> EmailContent(
                lang,
                subject = if (wrapUp) "Wrap up your lesson with $n" else "Homework for $n?",
                preheader = "A minute now keeps the roadmap and homework on track.",
                robbi = RobbiPose.IDLE, heading = "How did it go?",
                paragraphs = listOfNotNull(
                    "Your lesson with $n is over. Still open:",
                    "• Check off what you covered".takeIf { wrapUp },
                    "• Create homework".takeIf { homework },
                ),
                details = listOf("Lesson" to lessonTitle, "When" to whenText, "Student" to n),
                ctaLabel = "Wrap up lesson", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            RU -> EmailContent(
                lang,
                subject = if (wrapUp) "Итоги урока: $n" else "Домашнее задание: $n",
                preheader = "Минута сейчас — и план с домашними заданиями в порядке.",
                robbi = RobbiPose.IDLE, heading = "Как прошёл урок?",
                paragraphs = listOfNotNull(
                    "Урок закончился ($n). Осталось:",
                    "• Отметить, что прошли".takeIf { wrapUp },
                    "• Задать домашнее задание".takeIf { homework },
                ),
                details = listOf("Урок" to lessonTitle, "Когда" to whenText, "Ученик" to n),
                ctaLabel = "Подвести итоги", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            UK -> EmailContent(
                lang,
                subject = if (wrapUp) "Підсумки уроку: $n" else "Домашнє завдання: $n",
                preheader = "Хвилина зараз — і план із домашніми завданнями в порядку.",
                robbi = RobbiPose.IDLE, heading = "Як минув урок?",
                paragraphs = listOfNotNull(
                    "Урок закінчився ($n). Залишилося:",
                    "• Позначити, що пройшли".takeIf { wrapUp },
                    "• Задати домашнє завдання".takeIf { homework },
                ),
                details = listOf("Урок" to lessonTitle, "Коли" to whenText, "Учень" to n),
                ctaLabel = "Підбити підсумки", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            DE -> EmailContent(
                lang,
                subject = if (wrapUp) "Schließ die Stunde mit $n ab" else "Hausaufgabe für $n?",
                preheader = "Eine Minute jetzt hält Lernplan und Hausaufgaben aktuell.",
                robbi = RobbiPose.IDLE, heading = "Wie lief die Stunde?",
                paragraphs = listOfNotNull(
                    "Deine Stunde mit $n ist vorbei. Noch offen:",
                    "• Abhaken, was ihr geschafft habt".takeIf { wrapUp },
                    "• Hausaufgabe erstellen".takeIf { homework },
                ),
                details = listOf("Stunde" to lessonTitle, "Wann" to whenText, "Lernende" to n),
                ctaLabel = "Stunde abschließen", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
        }
        return render(content)
    }

    /** To the teacher before a 1:1 that is not prepared yet; links the preparation assistant. */
    fun teacherPrep(
        lang: EmailLanguage,
        studentFirstName: String,
        lessonTitle: String,
        startsAt: OffsetDateTime,
        zone: ZoneId,
        url: String,
        footer: ReminderFooter,
    ): RenderedEmail {
        val n = studentFirstName
        val whenText = dateTime(lang, startsAt, zone)
        val time = time(startsAt, zone)
        val content = when (lang) {
            EN -> EmailContent(
                lang, subject = "Prepare your lesson with $n", preheader = "Your lesson starts at $time.",
                robbi = RobbiPose.WAVE, heading = "Time to prepare",
                paragraphs = listOf("Your next lesson with $n is coming up. The preparation assistant drafts a plan from the roadmap and your last lessons."),
                details = listOf("Lesson" to lessonTitle, "When" to whenText, "Student" to n),
                ctaLabel = "Prepare lesson", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            RU -> EmailContent(
                lang, subject = "Подготовка к уроку: $n", preheader = "Урок начнётся в $time.",
                robbi = RobbiPose.WAVE, heading = "Пора готовиться",
                paragraphs = listOf("Скоро следующий урок ($n). Помощник подготовки составит план по учебному плану и прошлым урокам."),
                details = listOf("Урок" to lessonTitle, "Когда" to whenText, "Ученик" to n),
                ctaLabel = "Подготовить урок", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            UK -> EmailContent(
                lang, subject = "Підготовка до уроку: $n", preheader = "Урок почнеться о $time.",
                robbi = RobbiPose.WAVE, heading = "Час готуватися",
                paragraphs = listOf("Незабаром наступний урок ($n). Помічник підготовки складе план за навчальним планом і минулими уроками."),
                details = listOf("Урок" to lessonTitle, "Коли" to whenText, "Учень" to n),
                ctaLabel = "Підготувати урок", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            DE -> EmailContent(
                lang, subject = "Bereite deine Stunde mit $n vor", preheader = "Die Stunde beginnt um $time.",
                robbi = RobbiPose.WAVE, heading = "Zeit für die Vorbereitung",
                paragraphs = listOf("Deine nächste Stunde mit $n steht bevor. Der Vorbereitungsassistent entwirft einen Plan aus dem Lernplan und den letzten Stunden."),
                details = listOf("Stunde" to lessonTitle, "Wann" to whenText, "Lernende" to n),
                ctaLabel = "Stunde vorbereiten", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
        }
        return render(content)
    }

    /** The weekly overview: one line per student (first name and reason counts, no content). */
    fun teacherDigest(lang: EmailLanguage, students: List<TeacherDigestLine>, url: String, footer: ReminderFooter): RenderedEmail {
        val count = students.size
        val details = students.map { line -> line.firstName to line.reasons.entries.joinToString("\n") { (type, n) -> digestReason(lang, type, n) } }
        val content = when (lang) {
            EN -> EmailContent(
                lang, subject = "Your week: $count ${if (count == 1) "student needs" else "students need"} attention",
                preheader = "Who is behind, what is not wrapped up, who has no homework.",
                robbi = RobbiPose.IDLE, heading = "Weekly overview",
                paragraphs = listOf("Here is who could use a little extra attention this week:"),
                details = details, ctaLabel = "Open dashboard", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            RU -> EmailContent(
                lang, subject = "Обзор недели: учеников, которым нужно внимание — $count",
                preheader = "Кто отстаёт, где нет итогов урока, у кого нет домашних заданий.",
                robbi = RobbiPose.IDLE, heading = "Обзор недели",
                paragraphs = listOf("Вот кому на этой неделе стоит уделить чуть больше внимания:"),
                details = details, ctaLabel = "Открыть панель", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            UK -> EmailContent(
                lang, subject = "Огляд тижня: учнів, яким потрібна увага — $count",
                preheader = "Хто відстає, де немає підсумків уроку, у кого немає домашніх завдань.",
                robbi = RobbiPose.IDLE, heading = "Огляд тижня",
                paragraphs = listOf("Ось кому цього тижня варто приділити трохи більше уваги:"),
                details = details, ctaLabel = "Відкрити панель", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
            DE -> EmailContent(
                lang, subject = "Deine Woche: Lernende, die Aufmerksamkeit brauchen – $count",
                preheader = "Wer hinter dem Plan liegt, was nicht abgeschlossen ist, wer keine Hausaufgabe hat.",
                robbi = RobbiPose.IDLE, heading = "Wochenüberblick",
                paragraphs = listOf("Diese Lernenden könnten diese Woche etwas mehr Aufmerksamkeit gebrauchen:"),
                details = details, ctaLabel = "Zum Dashboard", ctaUrl = url, linkHint = linkHint(lang),
                signoff = signoff(lang), footerReason = teacherReason(lang), footerLinks = footerLinks(lang, footer),
            )
        }
        return render(content)
    }

    private fun teacherReason(lang: EmailLanguage): String = when (lang) {
        EN -> "You're getting this because teacher reminders are on in your ${brand.name} account."
        RU -> "Вы получили это письмо, потому что в вашем аккаунте ${brand.name} включены напоминания для преподавателя."
        UK -> "Ви отримали цей лист, бо у вашому акаунті ${brand.name} увімкнено нагадування для викладача."
        DE -> "Du bekommst diese E-Mail, weil in deinem ${brand.name}-Konto Erinnerungen für Lehrkräfte aktiviert sind."
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

/** One weekly digest reason (email and Telegram); counts go after a colon so no plural forms are needed. */
fun digestReason(lang: EmailLanguage, type: AttentionReasonType, count: Int): String = when (lang) {
    EN -> when (type) {
        AttentionReasonType.BEHIND -> "behind schedule"
        AttentionReasonType.MISMATCH -> "self-assessment mismatches: $count"
        AttentionReasonType.NOT_WRAPPED_UP -> "lessons not wrapped up: $count"
        AttentionReasonType.NO_HOMEWORK -> "no homework for 2 weeks"
    }
    RU -> when (type) {
        AttentionReasonType.BEHIND -> "отстаёт от плана"
        AttentionReasonType.MISMATCH -> "расхождения в самооценке: $count"
        AttentionReasonType.NOT_WRAPPED_UP -> "уроки без итогов: $count"
        AttentionReasonType.NO_HOMEWORK -> "нет домашних заданий 2 недели"
    }
    UK -> when (type) {
        AttentionReasonType.BEHIND -> "відстає від плану"
        AttentionReasonType.MISMATCH -> "розбіжності в самооцінці: $count"
        AttentionReasonType.NOT_WRAPPED_UP -> "уроки без підсумків: $count"
        AttentionReasonType.NO_HOMEWORK -> "немає домашніх завдань 2 тижні"
    }
    DE -> when (type) {
        AttentionReasonType.BEHIND -> "hinter dem Plan"
        AttentionReasonType.MISMATCH -> "abweichende Selbsteinschätzung: $count"
        AttentionReasonType.NOT_WRAPPED_UP -> "Stunden ohne Abschluss: $count"
        AttentionReasonType.NO_HOMEWORK -> "seit 2 Wochen keine Hausaufgabe"
    }
}

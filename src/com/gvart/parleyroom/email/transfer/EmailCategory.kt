package com.gvart.parleyroom.email.transfer

/**
 * A group of emails a user can switch off (settings page or unsubscribe link). TEACHER_REMINDERS: the teacher's
 * follow-up, preparation and digest emails, toggled per type in /users/me/teacher-reminders (unsubscribe: all off).
 * LESSON_REMINDERS and HOMEWORK_REMINDERS also cover the booking and homework event emails; VOCAB_REMINDERS is the
 * daily "words due for review" email.
 */
enum class EmailCategory { LESSON_REMINDERS, HOMEWORK_REMINDERS, TEACHER_REMINDERS, VOCAB_REMINDERS }

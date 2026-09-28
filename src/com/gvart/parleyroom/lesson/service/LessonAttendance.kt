package com.gvart.parleyroom.lesson.service

/**
 * The single "lesson took place for this student" rule (API.md), shared by the library's
 * covered-by and student progress so they never disagree: the participant is CONFIRMED, the
 * lesson is not CANCELLED / REQUEST, and it is COMPLETED / IN_PROGRESS (started or finished early)
 * or its scheduled time has passed.
 */
object LessonAttendance {

    /** SQL condition over the aliases `l` (lessons) and `ls` (lesson_students). */
    const val TOOK_PLACE =
        "(ls.status = 'CONFIRMED' AND l.status NOT IN ('CANCELLED', 'REQUEST') " +
                "AND (l.status IN ('COMPLETED', 'IN_PROGRESS') OR l.scheduled_at <= now()))"
}

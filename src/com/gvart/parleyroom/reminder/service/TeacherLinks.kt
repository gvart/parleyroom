package com.gvart.parleyroom.reminder.service

/** Portal pages teacher reminders link to ([base]: the portal origin). */
object TeacherLinks {
    fun wrapUp(base: String, lesson: TeacherLesson) = "$base/teacher/students/${lesson.studentId}/wrap-up?lesson=${lesson.id}"

    fun prep(base: String, lesson: TeacherLesson) = "$base/teacher/students/${lesson.studentId}/prepare?lesson=${lesson.id}"

    fun dashboard(base: String) = "$base/teacher/dashboard"
}

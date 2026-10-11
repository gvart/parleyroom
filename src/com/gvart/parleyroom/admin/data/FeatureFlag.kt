package com.gvart.parleyroom.admin.data

/** Features that ship dark until an admin turns them on. [key] is the name in the API and the DB. */
enum class FeatureFlag(val key: String) {
    ROADMAP("roadmap"),
    LESSON_PREP("lessonPrep"),
    TEACHER_REMINDERS("teacherReminders"),
}

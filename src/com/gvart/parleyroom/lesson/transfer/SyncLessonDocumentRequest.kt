package com.gvart.parleyroom.lesson.transfer

import kotlinx.serialization.Serializable

/**
 * Field-keyed patch to the lesson document. Exactly one surface at a time.
 * Legacy clients may send `notes` without `field`; server treats that as
 * the role-appropriate private notes field (teacherNotes / studentNotes).
 */
@Serializable
data class SyncLessonDocumentRequest(
    val field: String? = null,
    val value: String? = null,
    val notes: String? = null,
)

enum class LessonDocumentField {
    SHARED_DOCUMENT,
    TEACHER_NOTES,
    STUDENT_NOTES,
    TEACHER_WENT_WELL,
    TEACHER_WORKING_ON,
    STUDENT_REFLECTION,
    STUDENT_HARD_TODAY;

    companion object {
        fun fromClient(raw: String): LessonDocumentField? = when (raw) {
            "sharedDocument" -> SHARED_DOCUMENT
            "teacherNotes" -> TEACHER_NOTES
            "studentNotes" -> STUDENT_NOTES
            "teacherWentWell" -> TEACHER_WENT_WELL
            "teacherWorkingOn" -> TEACHER_WORKING_ON
            "studentReflection" -> STUDENT_REFLECTION
            "studentHardToday" -> STUDENT_HARD_TODAY
            else -> null
        }
    }
}

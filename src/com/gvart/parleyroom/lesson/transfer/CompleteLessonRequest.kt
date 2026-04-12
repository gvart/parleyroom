package com.gvart.parleyroom.lesson.transfer

import kotlinx.serialization.Serializable

@Serializable
data class CompleteLessonRequest(
    val teacherNotes: String? = null,
    val teacherWentWell: String? = null,
    val teacherWorkingOn: String? = null,
)

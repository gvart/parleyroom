package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import kotlinx.serialization.Serializable

/** REQUESTED, or CONFIRMED when the teacher auto-accepts club joins. */
@Serializable
data class JoinLessonResponse(val status: LessonStudentStatus)

package com.gvart.parleyroom.lesson.transfer

import kotlinx.serialization.Serializable

@Serializable
data class ReflectLessonRequest(
    val studentReflection: String? = null,
    val studentHardToday: String? = null,
)

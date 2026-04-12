package com.gvart.parleyroom.lesson.transfer

import kotlinx.serialization.Serializable

@Serializable
data class CancelLessonRequest(
    val reason: String? = null,
)

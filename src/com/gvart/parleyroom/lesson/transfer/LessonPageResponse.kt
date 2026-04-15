package com.gvart.parleyroom.lesson.transfer

import kotlinx.serialization.Serializable

@Serializable
data class LessonPageResponse(
    val lessons: List<LessonResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

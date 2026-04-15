package com.gvart.parleyroom.homework.transfer

import kotlinx.serialization.Serializable

@Serializable
data class HomeworkPageResponse(
    val homework: List<HomeworkResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

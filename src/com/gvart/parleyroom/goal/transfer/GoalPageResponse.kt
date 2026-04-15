package com.gvart.parleyroom.goal.transfer

import kotlinx.serialization.Serializable

@Serializable
data class GoalPageResponse(
    val goals: List<GoalResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

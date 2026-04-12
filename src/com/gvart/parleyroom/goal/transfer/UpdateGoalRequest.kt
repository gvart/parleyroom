package com.gvart.parleyroom.goal.transfer

import kotlinx.serialization.Serializable

@Serializable
data class UpdateGoalRequest(
    val description: String? = null,
    val targetDate: String? = null,
)

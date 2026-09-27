package com.gvart.parleyroom.activity.transfer

import kotlinx.serialization.Serializable

@Serializable
data class StreakResponse(
    val current: Int,
    val longest: Int,
    val todayDone: Boolean,
    val week: List<StreakDay>,
)

@Serializable
data class StreakDay(
    val date: String,
    val active: Boolean,
)

package com.gvart.parleyroom.goal.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.goal.data.GoalSetBy
import com.gvart.parleyroom.goal.data.GoalStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class GoalResponse(
    val id: String,
    val studentId: String,
    val teacherId: String? = null,
    val description: String,
    val progress: Int,
    val setBy: GoalSetBy,
    val targetDate: String? = null,
    val status: GoalStatus,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

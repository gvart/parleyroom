package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class AttachMaterialsRequest(
    val materialIds: List<String>,
)

@Serializable
data class LessonMaterialResponse(
    val material: MaterialResponse,
    val attachedBy: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val attachedAt: OffsetDateTime,
)

@Serializable
data class LessonMaterialListResponse(
    val items: List<LessonMaterialResponse>,
)

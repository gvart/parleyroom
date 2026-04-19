package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class ShareRequest(
    val studentIds: List<String>,
)

@Serializable
data class ShareGrantResponse(
    val studentId: String,
    val firstName: String,
    val lastName: String,
    val sharedBy: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val sharedAt: OffsetDateTime,
)

@Serializable
data class ShareListResponse(
    val grants: List<ShareGrantResponse>,
)

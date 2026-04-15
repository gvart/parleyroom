package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.material.data.MaterialType
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class MaterialResponse(
    val id: String,
    val teacherId: String,
    val studentId: String? = null,
    val lessonId: String? = null,
    val name: String,
    val type: MaterialType,
    val contentType: String? = null,
    val fileSize: Long? = null,
    val downloadUrl: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

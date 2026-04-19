package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.material.data.MaterialSkill
import com.gvart.parleyroom.material.data.MaterialType
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class MaterialResponse(
    val id: String,
    val teacherId: String,
    val folderId: String? = null,
    val name: String,
    val type: MaterialType,
    val level: LanguageLevel? = null,
    val skill: MaterialSkill? = null,
    val contentType: String? = null,
    val fileSize: Long? = null,
    val downloadUrl: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
)

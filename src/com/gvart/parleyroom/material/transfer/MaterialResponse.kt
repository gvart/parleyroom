package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.material.data.MaterialType
import kotlinx.serialization.Serializable

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
    val createdAt: String,
)

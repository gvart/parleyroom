package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.material.data.MaterialType
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class CreateMaterialRequest(
    val name: String,
    val type: MaterialType,
    val studentId: String? = null,
    val lessonId: String? = null,
    val fileName: String? = null,
    val contentType: String? = null,
    val fileSize: Long? = null,
    val url: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (name.isBlank()) add("Name can't be empty")
            when (type) {
                MaterialType.LINK -> {
                    if (url.isNullOrBlank()) add("url is required for LINK materials")
                }
                MaterialType.PDF, MaterialType.AUDIO, MaterialType.VIDEO -> {
                    if (fileName.isNullOrBlank()) add("fileName is required for uploaded materials")
                    if (contentType.isNullOrBlank()) add("contentType is required for uploaded materials")
                }
            }
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}

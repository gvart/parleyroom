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
    val url: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (name.isBlank()) add("Name can't be empty")
            if (type == MaterialType.LINK && url.isNullOrBlank()) {
                add("url is required for LINK materials")
            }
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}
package com.gvart.parleyroom.material.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
enum class BulkMaterialAction { MOVE, SHARE, DELETE }

@Serializable
data class BulkMaterialRequest(
    val action: BulkMaterialAction,
    val materialIds: List<String>,
    val targetFolderId: String? = null,
    val moveToRoot: Boolean = false,
    val studentIds: List<String>? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (materialIds.isEmpty()) add("materialIds is required")
            when (action) {
                BulkMaterialAction.MOVE -> {
                    if (targetFolderId == null && !moveToRoot) add("targetFolderId or moveToRoot=true is required for MOVE")
                }
                BulkMaterialAction.SHARE -> {
                    if (studentIds.isNullOrEmpty()) add("studentIds is required for SHARE")
                }
                BulkMaterialAction.DELETE -> Unit
            }
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors) else ValidationResult.Valid
    }
}

@Serializable
data class BulkMaterialResponse(
    val action: BulkMaterialAction,
    val affected: Int,
)

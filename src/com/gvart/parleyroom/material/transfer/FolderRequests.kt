package com.gvart.parleyroom.material.transfer

import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class CreateFolderRequest(
    val name: String,
    val parentFolderId: String? = null,
) {
    fun validate(): ValidationResult =
        if (name.isBlank()) ValidationResult.Invalid(listOf("Name can't be empty"))
        else ValidationResult.Valid
}

/**
 * PATCH body for a folder. Only non-null fields are applied.
 * To move to the root, send `{"parentFolderId": null, "moveToRoot": true}`.
 */
@Serializable
data class UpdateFolderRequest(
    val name: String? = null,
    val parentFolderId: String? = null,
    val moveToRoot: Boolean = false,
)

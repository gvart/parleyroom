package com.gvart.parleyroom.material.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class MaterialFolderResponse(
    val id: String,
    val teacherId: String,
    val parentFolderId: String? = null,
    val name: String,
    val materialCount: Int = 0,
    val childFolderCount: Int = 0,
    val sharedWithCount: Int = 0,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class FolderTreeNode(
    val folder: MaterialFolderResponse,
    val children: List<FolderTreeNode> = emptyList(),
)

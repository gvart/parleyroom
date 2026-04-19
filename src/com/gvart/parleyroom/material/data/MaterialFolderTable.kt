package com.gvart.parleyroom.material.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object MaterialFolderTable : UUIDTable("material_folders") {
    val parentFolderId = reference("parent_folder_id", MaterialFolderTable).nullable()
    val teacherId = reference("teacher_id", UserTable)
    val name = varchar("name", 255)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

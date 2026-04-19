package com.gvart.parleyroom.material.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

enum class MaterialType { PDF, AUDIO, VIDEO, LINK }

object MaterialTable : UUIDTable("materials") {
    val teacherId = reference("teacher_id", UserTable)
    val folderId = reference("folder_id", MaterialFolderTable).nullable()
    val name = varchar("name", 255)
    val type = pgEnum<MaterialType>("type", "MATERIAL_TYPE")
    val url = text("url")
    val contentType = varchar("content_type", 100).nullable()
    val fileSize = long("file_size").nullable()
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val skill = pgEnum<MaterialSkill>("skill", "MATERIAL_SKILL").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

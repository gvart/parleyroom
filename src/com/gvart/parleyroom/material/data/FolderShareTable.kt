package com.gvart.parleyroom.material.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object FolderShareTable : Table("folder_shares") {
    val folderId = reference("folder_id", MaterialFolderTable)
    val studentId = reference("student_id", UserTable)
    val sharedBy = reference("shared_by", UserTable)
    val sharedAt = timestampWithTimeZone("shared_at")

    override val primaryKey = PrimaryKey(folderId, studentId)
}

package com.gvart.parleyroom.topic.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object TopicTable : UUIDTable("topics") {
    val teacherId = reference("teacher_id", UserTable)
    val parentId = reference("parent_id", TopicTable).nullable()
    val name = varchar("name", 255)
    val levels = array<String>("levels", VarCharColumnType(2))
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

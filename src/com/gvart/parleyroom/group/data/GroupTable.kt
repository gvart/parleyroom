package com.gvart.parleyroom.group.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class GroupType { SPEECH, READING }

object GroupTable : UUIDTable("study_groups") {
    val teacherId = reference("teacher_id", UserTable)
    val name = varchar("name", 255)
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val type = pgEnum<GroupType>("type", "GROUP_TYPE")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

object GroupMemberTable : Table("group_members") {
    val groupId = reference("group_id", GroupTable)
    val studentId = reference("student_id", UserTable)
    val addedAt = timestampWithTimeZone("added_at")

    override val primaryKey = PrimaryKey(groupId, studentId)
}

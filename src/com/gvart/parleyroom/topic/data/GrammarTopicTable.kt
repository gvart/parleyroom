package com.gvart.parleyroom.topic.data

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object GrammarTopicTable : UUIDTable("grammar_topics") {
    val teacherId = reference("teacher_id", UserTable)
    val name = varchar("name", 255)
    val level = pgEnum<LanguageLevel>("level", "LANGUAGE_LEVEL").nullable()
    val category = varchar("category", 100).nullable()
    val explanation = text("explanation").nullable()
    val examples = array<String>("examples", TextColumnType())
    val position = integer("position").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

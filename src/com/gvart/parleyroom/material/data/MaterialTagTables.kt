package com.gvart.parleyroom.material.data

import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import org.jetbrains.exposed.v1.core.Table

object MaterialTopicTable : Table("material_topics") {
    val materialId = reference("material_id", MaterialTable)
    val topicId = reference("topic_id", TopicTable)

    override val primaryKey = PrimaryKey(materialId, topicId)
}

object MaterialGrammarTopicTable : Table("material_grammar_topics") {
    val materialId = reference("material_id", MaterialTable)
    val grammarTopicId = reference("grammar_topic_id", GrammarTopicTable)

    override val primaryKey = PrimaryKey(materialId, grammarTopicId)
}

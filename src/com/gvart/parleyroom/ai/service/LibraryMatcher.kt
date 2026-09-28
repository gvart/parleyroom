package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.WordType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime
import java.util.UUID

/** Name-based matching against (and find-or-create in) a teacher's library. Must run in a transaction. */
class LibraryMatcher(private val teacherId: UUID) {

    private data class Topic(val id: UUID, val parentId: UUID?, val name: String)

    private val topics: MutableList<Topic> by lazy {
        TopicTable.selectAll().where { TopicTable.teacherId eq teacherId }
            .map { Topic(it[TopicTable.id].value, it[TopicTable.parentId]?.value, it[TopicTable.name]) }
            .toMutableList()
    }

    /** Case-insensitive name match; with [parentName] the topic under that parent wins. */
    fun matchTopic(name: String, parentName: String?): UUID? {
        val candidates = topics.filter { it.name.equals(name.trim(), ignoreCase = true) }
        if (candidates.size <= 1 || parentName == null) return candidates.firstOrNull()?.id
        val parents = topics.filter { it.name.equals(parentName.trim(), ignoreCase = true) }.map { it.id }.toSet()
        return (candidates.firstOrNull { it.parentId in parents } ?: candidates.first()).id
    }

    fun matchGrammar(name: String): UUID? = GrammarTopicTable.selectAll()
        .where { (GrammarTopicTable.teacherId eq teacherId) and (GrammarTopicTable.name.lowerCase() eq name.trim().lowercase()) }
        .singleOrNull()?.get(GrammarTopicTable.id)?.value

    /** The library dedupe key: lower(lemma) + article + wordType. */
    fun matchEntry(lemma: String, article: NounArticle?, wordType: WordType): ResultRow? {
        val articleCond: Op<Boolean> = article?.let { VocabEntryTable.article eq it } ?: VocabEntryTable.article.isNull()
        return VocabEntryTable.selectAll().where {
            (VocabEntryTable.teacherId eq teacherId) and
                    (VocabEntryTable.lemma.lowerCase() eq lemma.trim().lowercase()) and
                    (VocabEntryTable.wordType eq wordType) and articleCond
        }.singleOrNull()
    }

    /** Same name under the same parent, else a new topic. Returns (id, reused). */
    fun findOrCreateTopic(name: String, parentId: UUID?, level: LanguageLevel?): Pair<UUID, Boolean> {
        topics.firstOrNull { it.parentId == parentId && it.name.equals(name.trim(), ignoreCase = true) }?.let { return it.id to true }
        val now = OffsetDateTime.now()
        val id = TopicTable.insertAndGetId {
            it[TopicTable.teacherId] = this@LibraryMatcher.teacherId
            it[TopicTable.parentId] = parentId
            it[TopicTable.name] = name.trim()
            it[levels] = listOfNotNull(level?.name)
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        topics += Topic(id, parentId, name.trim())
        return id to false
    }

    /** Grammar topic names are unique per teacher (case-insensitive). Returns (id, reused). */
    fun findOrCreateGrammar(name: String, level: LanguageLevel?): Pair<UUID, Boolean> {
        matchGrammar(name)?.let { return it to true }
        val now = OffsetDateTime.now()
        val id = GrammarTopicTable.insertAndGetId {
            it[GrammarTopicTable.teacherId] = this@LibraryMatcher.teacherId
            it[GrammarTopicTable.name] = name.trim()
            it[GrammarTopicTable.level] = level
            it[examples] = emptyList()
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        return id to false
    }
}

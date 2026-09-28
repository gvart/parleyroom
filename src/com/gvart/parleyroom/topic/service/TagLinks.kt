package com.gvart.parleyroom.topic.service

import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.common.transfer.TagUsage
import java.util.UUID

/** One join table tagging library items (`item` column) with a topic or grammar topic (`tag` column). */
class TagLink(val table: String, val item: String, val tag: String)

/**
 * The join tables that carry topic / grammar topic tags, and set-based operations over them
 * (usage counts, merge re-pointing with dedupe). Names are constants, never user input.
 * Must run in a transaction.
 */
class TagLinks(
    private val words: TagLink?,
    private val documents: TagLink,
    private val materials: TagLink,
    private val lessons: TagLink,
) {
    private val all = listOfNotNull(words, documents, materials, lessons)

    fun usage(tagId: UUID) = TagUsage(
        words = words?.let { count(it, tagId) } ?: 0,
        documents = count(documents, tagId),
        materials = count(materials, tagId),
        lessons = count(lessons, tagId),
    )

    /** Rows of [source] that a merge into [target] would re-point (rows already on the target are not counted). */
    fun movable(source: UUID, target: UUID) = TagUsage(
        words = words?.let { countMovable(it, source, target) } ?: 0,
        documents = countMovable(documents, source, target),
        materials = countMovable(materials, source, target),
        lessons = countMovable(lessons, source, target),
    )

    /** Re-points every tag of [source] to [target]; items tagged with both keep one row. */
    fun repoint(source: UUID, target: UUID) = all.forEach { link ->
        Sql.update(
            "INSERT INTO ${link.table} (${link.item}, ${link.tag}) " +
                    "SELECT ${link.item}, ? FROM ${link.table} WHERE ${link.tag} = ? ON CONFLICT DO NOTHING",
            target, source,
        )
        Sql.update("DELETE FROM ${link.table} WHERE ${link.tag} = ?", source)
    }

    private fun count(link: TagLink, tagId: UUID) =
        Sql.long("SELECT count(*) FROM ${link.table} WHERE ${link.tag} = ?", tagId)

    private fun countMovable(link: TagLink, source: UUID, target: UUID) = Sql.long(
        "SELECT count(*) FROM ${link.table} a WHERE a.${link.tag} = ? AND NOT EXISTS " +
                "(SELECT 1 FROM ${link.table} b WHERE b.${link.item} = a.${link.item} AND b.${link.tag} = ?)",
        source, target,
    )

    companion object {
        val TOPICS = TagLinks(
            words = TagLink("vocab_entry_topics", "vocab_entry_id", "topic_id"),
            documents = TagLink("document_topics", "document_id", "topic_id"),
            materials = TagLink("material_topics", "material_id", "topic_id"),
            lessons = TagLink("lesson_topics", "lesson_id", "topic_id"),
        )
        val GRAMMAR = TagLinks(
            words = null,
            documents = TagLink("document_grammar_topics", "document_id", "grammar_topic_id"),
            materials = TagLink("material_grammar_topics", "material_id", "grammar_topic_id"),
            lessons = TagLink("lesson_grammar_topics", "lesson_id", "grammar_topic_id"),
        )
    }
}

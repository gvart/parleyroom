package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.transfer.DraftGrammarTopic
import com.gvart.parleyroom.ai.transfer.DraftTopic
import com.gvart.parleyroom.topic.transfer.GrammarTopicRef
import java.util.UUID

/** A library topic with its path ("Alltag > Haushalt"). */
data class LibraryTopic(val id: UUID, val name: String, val parentName: String?, val path: String)

/**
 * The teacher's topics and grammar topics under short keys (T1…, G1…) for the model: cheaper than
 * UUIDs and never mistaken for them. Keys follow the list order, so they are only valid within
 * the prompt they were rendered into.
 */
class TagCatalog(val topics: List<LibraryTopic>, val grammar: List<GrammarTopicRef>) {

    private val topicByKey = topics.withIndex().associate { (i, t) -> "T${i + 1}" to t }
    private val grammarByKey = grammar.withIndex().associate { (i, g) -> "G${i + 1}" to g }
    private val topicKeys = topicByKey.entries.associate { (key, t) -> t.id.toString() to key }
    private val grammarKeys = grammarByKey.entries.associate { (key, g) -> g.id to key }

    fun topicLines(): String = topicByKey.entries.joinToString("\n") { (key, t) -> "$key: ${t.path}" }

    fun grammarLines(): String = grammarByKey.entries.joinToString("\n") { (key, g) ->
        "$key: ${g.name}" + (g.level?.let { " ($it)" } ?: "")
    }

    /** The model's tag: a known key is the library topic, otherwise its name is matched or proposed as new. */
    fun topic(tag: AiTopic, matcher: LibraryMatcher): DraftTopic? {
        tag.id?.let(topicByKey::get)?.let { return DraftTopic(it.id.toString(), it.name, it.parentName) }
        if (tag.name.isBlank()) return null
        return DraftTopic(matcher.matchTopic(tag.name, tag.parentName)?.toString(), tag.name, tag.parentName)
    }

    fun grammar(tag: AiGrammarTopic, matcher: LibraryMatcher): DraftGrammarTopic? {
        tag.id?.let(grammarByKey::get)?.let { return DraftGrammarTopic(it.id, it.name, it.level) }
        if (tag.name.isBlank()) return null
        return DraftGrammarTopic(matcher.matchGrammar(tag.name)?.toString(), tag.name, tag.level)
    }

    /** A draft tag back in the model's form: the key when it is in the library, else the name. */
    fun toAi(tag: DraftTopic): AiTopic =
        tag.id?.let(topicKeys::get)?.let { AiTopic(id = it) } ?: AiTopic(tag.name, tag.parentName)

    fun toAi(tag: DraftGrammarTopic): AiGrammarTopic =
        tag.id?.let(grammarKeys::get)?.let { AiGrammarTopic(id = it) } ?: AiGrammarTopic(tag.name, tag.level)
}

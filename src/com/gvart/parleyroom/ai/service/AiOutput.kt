package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import com.gvart.parleyroom.lesson.transfer.CorrectedSentenceInput
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.service.VocabDisplay
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** What the model must return for GENERATE / REFINE (see API.md "AI output"). */
@Serializable
data class AiOutput(
    val vocab: List<AiVocab> = emptyList(),
    val document: AiDocument,
    val suggestedTopics: List<AiTopic> = emptyList(),
    val suggestedGrammarTopics: List<AiGrammarTopic> = emptyList(),
    val correctedSentences: List<CorrectedSentenceInput> = emptyList(),
)

@Serializable
data class AiVocab(
    val key: String,
    val lemma: String,
    val article: NounArticle? = null,
    val plural: String? = null,
    val wordType: WordType,
    val forms: String? = null,
    val government: String? = null,
    val translations: Map<String, String> = emptyMap(),
    val explanationDe: String? = null,
    val exampleSentence: String? = null,
    val level: LanguageLevel? = null,
    val synonyms: List<String> = emptyList(),
    val topicName: String? = null,
) {
    fun toInput(topicIds: List<String>, sourceLessonId: String?) = VocabEntryInput(
        lemma = lemma.trim(),
        article = article,
        plural = plural?.takeIf { it.isNotBlank() },
        wordType = wordType,
        forms = forms?.takeIf { it.isNotBlank() },
        government = government?.takeIf { it.isNotBlank() },
        translations = translations.filterValues { it.isNotBlank() },
        explanationDe = explanationDe?.takeIf { it.isNotBlank() },
        exampleSentence = exampleSentence?.takeIf { it.isNotBlank() },
        level = level,
        topicIds = topicIds,
        synonyms = synonyms.filter { it.isNotBlank() },
        sourceLessonId = sourceLessonId,
    )
}

@Serializable
data class AiDocument(val title: String, val blocks: JsonArray)

@Serializable
data class AiTopic(val name: String, val parentName: String? = null)

@Serializable
data class AiGrammarTopic(val name: String, val level: LanguageLevel? = null)

/** Model output for FILL_TRANSLATIONS. */
@Serializable
data class AiFillOutput(val entries: List<AiFilledEntry> = emptyList())

@Serializable
data class AiFilledEntry(
    val key: String,
    val translations: Map<String, String> = emptyMap(),
    val explanationDe: String? = null,
)

data class Issue(val pointer: String, val message: String)

/** A validation failure of model output; the issues are fed back to the model on retry. */
class AiOutputInvalid(val issues: List<Issue>) : Exception("AI output is invalid: " + issues.take(3).joinToString { "${it.pointer} ${it.message}" })

/** A validated generation output, with document blocks converted to real document blocks. */
data class ValidatedOutput(
    val output: AiOutput,
    val title: String,
    val blocks: JsonArray,
    /** Block id (uuid) -> vocab keys of each vocab_table. */
    val vocabTables: Map<String, List<String>>,
)

/**
 * Parses and validates model output. Never trusts the model: the JSON must decode, vocab must be
 * valid [VocabEntryInput]s, and the converted blocks must pass the published block schema and the
 * strict completeness profile.
 */
object AiOutputParser {

    const val MAX_VOCAB = 150
    private const val MAX_ISSUES = 30

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun parseGeneration(text: String): ValidatedOutput {
        val output = decode<AiOutput>(text)
        val issues = mutableListOf<Issue>()

        if (output.vocab.size > MAX_VOCAB) issues += Issue("/vocab", "at most $MAX_VOCAB words")
        val keys = mutableSetOf<String>()
        output.vocab.forEachIndexed { i, vocab ->
            val pointer = "/vocab/$i"
            if (vocab.key.isBlank()) issues += Issue("$pointer/key", "key is empty")
            else if (!keys.add(vocab.key)) issues += Issue("$pointer/key", "key ${vocab.key} is used twice")
            vocab.toInput(emptyList(), null).errors().forEach { issues += Issue(pointer, it) }
            val unsupported = vocab.translations.keys - VocabDisplay.TRANSLATION_LANGUAGES
            if (unsupported.isNotEmpty()) issues += Issue("$pointer/translations", "unsupported languages ${unsupported.joinToString()}; use ru, en")
        }
        output.suggestedTopics.forEachIndexed { i, t -> if (t.name.isBlank() || t.name.length > 255) issues += Issue("/suggestedTopics/$i/name", "name must be 1..255 characters") }
        output.suggestedGrammarTopics.forEachIndexed { i, g -> if (g.name.isBlank() || g.name.length > 255) issues += Issue("/suggestedGrammarTopics/$i/name", "name must be 1..255 characters") }
        output.correctedSentences.forEachIndexed { i, s ->
            if (s.incorrect.isBlank() || s.correct.isBlank()) issues += Issue("/correctedSentences/$i", "needs both incorrect and correct")
        }

        val title = output.document.title.trim()
        if (title.isEmpty() || title.length > 255) issues += Issue("/document/title", "title must be 1..255 characters")

        val converted = AiBlocks.fromAi(output.document.blocks)
        converted.vocabTables.forEach { (index, tableKeys) ->
            tableKeys.forEachIndexed { j, key ->
                if (key !in keys) issues += Issue("/document/blocks/$index/vocabKeys/$j", "unknown vocab key $key")
            }
        }
        try {
            DocumentBlockValidator.validate(converted.blocks)
            DocumentBlockValidator.completenessIssues(converted.blocks).forEach { issues += Issue("/document${it.pointer}", it.message) }
        } catch (e: BadRequestException) {
            issues += Issue("/document" + (e.pointer ?: "/blocks"), e.message ?: "invalid block")
        }

        if (issues.isNotEmpty()) throw AiOutputInvalid(issues.take(MAX_ISSUES))
        val tables = converted.vocabTables.mapKeys { (index, _) ->
            (converted.blocks[index].jsonObject["id"] as JsonPrimitive).content
        }
        return ValidatedOutput(output, title, converted.blocks, tables)
    }

    fun parseFill(text: String, expectedKeys: Set<String>): AiFillOutput {
        val output = decode<AiFillOutput>(text)
        val issues = mutableListOf<Issue>()
        output.entries.forEachIndexed { i, entry ->
            if (entry.key !in expectedKeys) issues += Issue("/entries/$i/key", "unknown key ${entry.key}")
            val unsupported = entry.translations.keys - VocabDisplay.TRANSLATION_LANGUAGES
            if (unsupported.isNotEmpty()) issues += Issue("/entries/$i/translations", "unsupported languages ${unsupported.joinToString()}")
        }
        if (issues.isNotEmpty()) throw AiOutputInvalid(issues.take(MAX_ISSUES))
        return output
    }

    private inline fun <reified T> decode(text: String): T {
        val body = extractJson(text) ?: throw AiOutputInvalid(listOf(Issue("", "answer is not a JSON object")))
        return try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            throw AiOutputInvalid(listOf(Issue("", "JSON does not match the required shape: ${e.message?.take(300)}")))
        } catch (e: IllegalArgumentException) {
            throw AiOutputInvalid(listOf(Issue("", "JSON does not match the required shape: ${e.message?.take(300)}")))
        }
    }

    /** The model should answer with bare JSON; tolerate a code fence or a sentence around it. */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0..<end) text.substring(start, end + 1) else null
    }
}

/**
 * Conversion between document blocks and the model-facing "AiBlock" form: short string ids instead
 * of uuids, and `vocabKeys` instead of `rows` in vocab tables (library entries do not exist yet).
 */
object AiBlocks {

    data class Converted(val blocks: JsonArray, val vocabTables: Map<Int, List<String>>)

    /** Assigns a fresh uuid per distinct id (remapping correctOptionIds) and turns vocabKeys into empty rows. */
    fun fromAi(blocks: JsonArray): Converted {
        val mapping = mutableMapOf<String, String>()
        fun remap(id: String) = mapping.getOrPut(id) { UUID.randomUUID().toString() }
        val tables = mutableMapOf<Int, List<String>>()
        val converted = blocks.mapIndexed { index, element ->
            val block = element as? JsonObject ?: return@mapIndexed element
            var copy = remapIds(block, ::remap) as JsonObject
            if ((block["type"] as? JsonPrimitive)?.content == "vocab_table") {
                val keys = (block["vocabKeys"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                tables[index] = keys
                copy = JsonObject(copy - "vocabKeys" + ("rows" to JsonArray(emptyList())))
            }
            if ("interactive" !in copy) copy = JsonObject(copy + ("interactive" to JsonPrimitive(false)))
            copy
        }
        return Converted(JsonArray(converted), tables)
    }

    /**
     * Document blocks -> AiBlocks for a refine: ids become b1, b2, … and each vocab_table lists the
     * keys of its rows' entries ([keyByEntryId]) plus the keys still waiting for publish ([pendingKeys]).
     */
    fun toAi(blocks: JsonArray, keyByEntryId: Map<String, String>, pendingKeys: Map<String, List<String>>): JsonArray {
        val mapping = mutableMapOf<String, String>()
        fun shorten(id: String) = mapping.getOrPut(id.lowercase()) { "i${mapping.size + 1}" }
        return JsonArray(blocks.map { element ->
            val block = element.jsonObject
            val blockId = (block["id"] as JsonPrimitive).content
            val short = remapIds(block, ::shorten) as JsonObject
            if ((block["type"] as? JsonPrimitive)?.content != "vocab_table") return@map short
            val fromRows = block["rows"]?.jsonArray.orEmpty()
                .mapNotNull { keyByEntryId[(it.jsonObject["vocabEntryId"] as JsonPrimitive).content] }
            val keys = (fromRows + pendingKeys[blockId].orEmpty()).distinct()
            JsonObject(short - "rows" - "topicId" + ("vocabKeys" to JsonArray(keys.map(::JsonPrimitive))))
        })
    }

    // Only blocks, items, options, questions and rows carry an "id" key (rich text never does).
    private fun remapIds(element: JsonElement, remap: (String) -> String): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            when {
                key == "id" && value is JsonPrimitive -> JsonPrimitive(remap(value.content))
                key == "correctOptionIds" && value is JsonArray ->
                    JsonArray(value.map { if (it is JsonPrimitive) JsonPrimitive(remap(it.content)) else it })
                else -> remapIds(value, remap)
            }
        })
        is JsonArray -> JsonArray(element.map { remapIds(it, remap) })
        else -> element
    }
}

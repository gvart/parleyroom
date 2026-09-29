package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.material.data.MaterialSkill
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.service.HomeworkUnits
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

/**
 * What the model must return for GENERATE / REFINE of a draft bundle (see API.md "AI drafts").
 * 1:1: [words] + [homework]; club: [notes] only. An item refine returns just that part.
 */
@Serializable
data class AiDraftOutput(
    val words: List<AiWord> = emptyList(),
    val homework: AiHomework? = null,
    val notes: AiDocument? = null,
)

@Serializable
data class AiHomework(val document: AiDocument? = null, val tasks: List<AiTask> = emptyList())

@Serializable
data class AiWord(
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
    val topics: List<AiTopic> = emptyList(),
    val grammarTopics: List<AiGrammarTopic> = emptyList(),
) {
    fun toInput(sourceLessonId: String?) = VocabEntryInput(
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
        synonyms = synonyms.filter { it.isNotBlank() },
        sourceLessonId = sourceLessonId,
    )
}

@Serializable
data class AiDocument(
    val title: String,
    val blocks: JsonArray,
    val topics: List<AiTopic> = emptyList(),
    val grammarTopics: List<AiGrammarTopic> = emptyList(),
)

@Serializable
data class AiTask(
    val title: String,
    val instructions: String,
    val responseType: HomeworkResponseType,
    val topics: List<AiTopic> = emptyList(),
    val grammarTopics: List<AiGrammarTopic> = emptyList(),
)

@Serializable
data class AiDocumentAnswer(val document: AiDocument)

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

/** SUGGEST_TAGS model output. */
@Serializable
data class AiSuggestTagsOutput(
    val level: LanguageLevel? = null,
    val skill: MaterialSkill? = null,
    val topics: List<AiTopic> = emptyList(),
    val grammarTopics: List<AiGrammarTopic> = emptyList(),
)

/** SENTENCE_FEEDBACK model output. */
@Serializable
data class AiSentenceFeedback(
    val isCorrect: Boolean,
    val corrected: String,
    val explanation: String,
    val explanationTranslation: String? = null,
    val usesWord: Boolean,
)

data class Issue(val pointer: String, val message: String)

/** A validation failure of model output; the issues are fed back to the model on retry. */
class AiOutputInvalid(val issues: List<Issue>) : Exception("AI output is invalid: " + issues.take(3).joinToString { "${it.pointer} ${it.message}" })

/** What a generate / refine asks the model for, and so what the answer must contain. */
enum class DraftTarget { BUNDLE, WORD, EXERCISE_DOCUMENT, TASK, NOTES_DOCUMENT }

/** A document from the model with real block ids. */
data class ValidatedDocument(val title: String, val blocks: JsonArray, val topics: List<AiTopic>, val grammarTopics: List<AiGrammarTopic>)

/** A validated draft answer; only the parts of the [DraftTarget] are set. */
data class ValidatedDraft(
    val words: List<AiWord> = emptyList(),
    val exerciseDocument: ValidatedDocument? = null,
    val tasks: List<AiTask> = emptyList(),
    val notesDocument: ValidatedDocument? = null,
)

/** A refined existing document: blocks with real ids plus the vocab keys of each vocab_table (block id -> keys). */
data class ValidatedDocumentRefine(val title: String, val blocks: JsonArray, val vocabTables: Map<String, List<String>>)

/**
 * Parses and validates model output. Never trusts the model: the JSON must decode, vocab must be
 * valid [VocabEntryInput]s, and the converted blocks must pass the published block schema and the
 * strict completeness profile.
 */
object AiOutputParser {

    const val MAX_WORDS = 150
    const val MIN_TASKS = 1
    const val MAX_TASKS = 3
    const val MAX_SUGGESTED_TAGS = 5
    private const val MAX_ISSUES = 30
    private const val MAX_TASK_TEXT = 5_000

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * A draft answer for [target]. BUNDLE (1:1) per [kinds]: WORDS = words (≤ [MAX_WORDS], at least one
     * when only words are asked for); HOMEWORK = one exercise document with at least one interactive
     * exercise and [MIN_TASKS]..[MAX_TASKS] tasks. Parts that were not asked for are ignored. A club bundle is its notes
     * document (NOTES_DOCUMENT). An item refine: exactly that one part. Other parts are ignored.
     */
    fun parseDraft(text: String, target: DraftTarget, kinds: Set<DraftKind> = DraftKind.entries.toSet()): ValidatedDraft {
        val output = decode<AiDraftOutput>(text)
        val issues = mutableListOf<Issue>()

        var words = emptyList<AiWord>()
        var exercise: ValidatedDocument? = null
        var tasks = emptyList<AiTask>()
        var notes: ValidatedDocument? = null

        when (target) {
            DraftTarget.NOTES_DOCUMENT -> {
                notes = output.notes?.let { document(it, "/notes", exercise = false, issues) }
                if (output.notes == null) issues += Issue("/notes", "notes (the notes document) is required")
            }
            DraftTarget.WORD -> {
                if (output.words.size != 1) issues += Issue("/words", "answer with exactly one word")
                words = output.words.take(1)
            }
            DraftTarget.TASK -> {
                val found = output.homework?.tasks.orEmpty()
                if (found.size != 1) issues += Issue("/homework/tasks", "answer with exactly one task")
                tasks = found.take(1)
            }
            DraftTarget.EXERCISE_DOCUMENT -> {
                exercise = output.homework?.document?.let { document(it, "/homework/document", exercise = true, issues) }
                if (output.homework?.document == null) issues += Issue("/homework/document", "homework.document is required")
            }
            DraftTarget.BUNDLE -> {
                if (DraftKind.WORDS in kinds) {
                    words = output.words
                    if (kinds.size == 1 && words.isEmpty()) issues += Issue("/words", "give at least one word")
                }
                if (DraftKind.HOMEWORK in kinds) {
                    val homework = output.homework
                    if (homework == null) issues += Issue("/homework", "homework is required")
                    exercise = homework?.document?.let { document(it, "/homework/document", exercise = true, issues) }
                    if (homework != null && homework.document == null) issues += Issue("/homework/document", "homework.document is required")
                    tasks = homework?.tasks.orEmpty()
                    if (homework != null && tasks.size !in MIN_TASKS..MAX_TASKS)
                        issues += Issue("/homework/tasks", "give $MIN_TASKS to $MAX_TASKS tasks")
                }
            }
        }
        checkWords(words, issues)
        tasks.forEachIndexed { i, task -> checkTask(task, "/homework/tasks/$i", issues) }

        if (issues.isNotEmpty()) throw AiOutputInvalid(issues.take(MAX_ISSUES))
        return ValidatedDraft(
            words = words.map { it.copy(topics = cleanTopics(it.topics), grammarTopics = cleanGrammar(it.grammarTopics)) },
            exerciseDocument = exercise,
            tasks = tasks.map {
                it.copy(title = it.title.trim(), instructions = it.instructions.trim(),
                    topics = cleanTopics(it.topics), grammarTopics = cleanGrammar(it.grammarTopics))
            },
            notesDocument = notes,
        )
    }

    /** A refined existing document: `{ "document": { title, blocks } }`, vocab tables may use [vocabKeys]. */
    fun parseDocumentRefine(text: String, vocabKeys: Set<String>): ValidatedDocumentRefine {
        val output = decode<AiDocumentAnswer>(text)
        val issues = mutableListOf<Issue>()
        val title = output.document.title.trim()
        if (title.isEmpty() || title.length > 255) issues += Issue("/document/title", "title must be 1..255 characters")
        val converted = AiBlocks.fromAi(output.document.blocks)
        converted.vocabTables.forEach { (index, keys) ->
            keys.forEachIndexed { j, key ->
                if (key !in vocabKeys) issues += Issue("/document/blocks/$index/vocabKeys/$j", "unknown vocab key $key")
            }
        }
        checkBlocks(converted.blocks, "/document", issues)
        if (issues.isNotEmpty()) throw AiOutputInvalid(issues.take(MAX_ISSUES))
        val tables = converted.vocabTables.mapKeys { (index, _) -> (converted.blocks[index].jsonObject["id"] as JsonPrimitive).content }
        return ValidatedDocumentRefine(title, converted.blocks, tables)
    }

    private fun document(doc: AiDocument, pointer: String, exercise: Boolean, issues: MutableList<Issue>): ValidatedDocument {
        val title = doc.title.trim()
        if (title.isEmpty() || title.length > 255) issues += Issue("$pointer/title", "title must be 1..255 characters")
        doc.blocks.forEachIndexed { i, block ->
            if (((block as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "vocab_table")
                issues += Issue("$pointer/blocks/$i", "vocab_table is not allowed here: words belong in words")
        }
        val blocks = AiBlocks.fromAi(doc.blocks).blocks
        checkBlocks(blocks, pointer, issues)
        if (exercise && issues.none { it.pointer.startsWith(pointer) } && HomeworkUnits.documentUnits(UUID.randomUUID(), blocks).isEmpty())
            issues += Issue("$pointer/blocks", "the homework document needs at least one exercise students answer in the app (\"interactive\": true)")
        checkTags(doc.topics, doc.grammarTopics, pointer, issues)
        return ValidatedDocument(title, blocks, cleanTopics(doc.topics), cleanGrammar(doc.grammarTopics))
    }

    private fun checkBlocks(blocks: JsonArray, pointer: String, issues: MutableList<Issue>) {
        try {
            DocumentBlockValidator.validate(blocks)
            DocumentBlockValidator.completenessIssues(blocks).forEach { issues += Issue("$pointer${it.pointer}", it.message) }
        } catch (e: BadRequestException) {
            issues += Issue(pointer + (e.pointer ?: "/blocks"), e.message ?: "invalid block")
        }
    }

    private fun checkWords(words: List<AiWord>, issues: MutableList<Issue>) {
        if (words.size > MAX_WORDS) issues += Issue("/words", "at most $MAX_WORDS words")
        val seen = mutableSetOf<String>()
        words.forEachIndexed { i, word ->
            val pointer = "/words/$i"
            word.toInput(null).errors().forEach { issues += Issue(pointer, it) }
            val unsupported = word.translations.keys - VocabDisplay.TRANSLATION_LANGUAGES
            if (unsupported.isNotEmpty()) issues += Issue("$pointer/translations", "unsupported languages ${unsupported.joinToString()}; use ru, en")
            if (!seen.add("${word.lemma.trim().lowercase()}|${word.article}|${word.wordType}"))
                issues += Issue("$pointer/lemma", "the word ${word.lemma} is listed twice")
            checkTags(word.topics, word.grammarTopics, pointer, issues)
        }
    }

    private fun checkTask(task: AiTask, pointer: String, issues: MutableList<Issue>) {
        if (task.title.isBlank() || task.title.trim().length > 255) issues += Issue("$pointer/title", "title must be 1..255 characters")
        if (task.instructions.isBlank() || task.instructions.length > MAX_TASK_TEXT)
            issues += Issue("$pointer/instructions", "instructions must be 1..$MAX_TASK_TEXT characters")
        checkTags(task.topics, task.grammarTopics, pointer, issues)
    }

    private fun checkTags(topics: List<AiTopic>, grammar: List<AiGrammarTopic>, pointer: String, issues: MutableList<Issue>) {
        if (topics.size > MAX_SUGGESTED_TAGS) issues += Issue("$pointer/topics", "at most $MAX_SUGGESTED_TAGS topics")
        if (grammar.size > MAX_SUGGESTED_TAGS) issues += Issue("$pointer/grammarTopics", "at most $MAX_SUGGESTED_TAGS grammar topics")
        topics.forEachIndexed { i, t -> if (t.name.isBlank() || t.name.length > 255) issues += Issue("$pointer/topics/$i/name", "name must be 1..255 characters") }
        grammar.forEachIndexed { i, g -> if (g.name.isBlank() || g.name.length > 255) issues += Issue("$pointer/grammarTopics/$i/name", "name must be 1..255 characters") }
    }

    private fun cleanTopics(topics: List<AiTopic>) =
        topics.map { it.copy(name = it.name.trim(), parentName = it.parentName?.trim()?.ifEmpty { null }) }.distinctBy { it.name.lowercase() }

    private fun cleanGrammar(grammar: List<AiGrammarTopic>) = grammar.map { it.copy(name = it.name.trim()) }.distinctBy { it.name.lowercase() }

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

    fun parseSuggestTags(text: String): AiSuggestTagsOutput {
        val output = decode<AiSuggestTagsOutput>(text)
        val issues = mutableListOf<Issue>()
        if (output.topics.size > MAX_SUGGESTED_TAGS) issues += Issue("/topics", "at most $MAX_SUGGESTED_TAGS topics")
        if (output.grammarTopics.size > MAX_SUGGESTED_TAGS) issues += Issue("/grammarTopics", "at most $MAX_SUGGESTED_TAGS grammar topics")
        output.topics.forEachIndexed { i, topic ->
            if (topic.name.isBlank() || topic.name.length > 255) issues += Issue("/topics/$i/name", "name must be 1..255 characters")
            if (topic.parentName != null && topic.parentName.length > 255) issues += Issue("/topics/$i/parentName", "at most 255 characters")
        }
        output.grammarTopics.forEachIndexed { i, grammar ->
            if (grammar.name.isBlank() || grammar.name.length > 255) issues += Issue("/grammarTopics/$i/name", "name must be 1..255 characters")
        }
        if (issues.isNotEmpty()) throw AiOutputInvalid(issues.take(MAX_ISSUES))
        return output.copy(topics = cleanTopics(output.topics), grammarTopics = cleanGrammar(output.grammarTopics))
    }

    const val MAX_FEEDBACK_LINE = 300
    const val MAX_CORRECTED = 1_000

    /** [translationRequested]: the prompt asked for `explanationTranslation`; otherwise it is dropped. */
    fun parseSentenceFeedback(text: String, translationRequested: Boolean): AiSentenceFeedback {
        val output = decode<AiSentenceFeedback>(text)
        val issues = mutableListOf<Issue>()
        fun line(pointer: String, value: String?) {
            if (value.isNullOrBlank()) issues += Issue(pointer, "must not be empty")
            else if (value.trim().length > MAX_FEEDBACK_LINE || '\n' in value.trim()) issues += Issue(pointer, "must be one line of at most $MAX_FEEDBACK_LINE characters")
        }
        if (output.corrected.isBlank() || output.corrected.length > MAX_CORRECTED) issues += Issue("/corrected", "must be 1..$MAX_CORRECTED characters")
        line("/explanation", output.explanation)
        if (translationRequested) line("/explanationTranslation", output.explanationTranslation)
        if (issues.isNotEmpty()) throw AiOutputInvalid(issues)
        return output.copy(
            corrected = output.corrected.trim(),
            explanation = output.explanation.trim(),
            explanationTranslation = if (translationRequested) output.explanationTranslation?.trim() else null,
        )
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

    /** Appends rows for [tables] (block id -> entry ids) to each vocab table still present (no duplicates). */
    fun fillRows(blocks: JsonArray, tables: Map<String, List<String>>): JsonArray = JsonArray(blocks.map { element ->
        val block = element as JsonObject
        val entryIds = tables[(block["id"] as JsonPrimitive).content] ?: return@map block
        val rows = (block["rows"] as? JsonArray).orEmpty()
        val present = rows.map { ((it as JsonObject)["vocabEntryId"] as JsonPrimitive).content.lowercase() }.toMutableSet()
        val added = entryIds.filter { present.add(it.lowercase()) }.map { entryId ->
            JsonObject(mapOf("id" to JsonPrimitive(UUID.randomUUID().toString()), "vocabEntryId" to JsonPrimitive(entryId)))
        }
        JsonObject(block + ("rows" to JsonArray(rows + added)))
    })

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

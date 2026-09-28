package com.gvart.parleyroom.ai.llm

import com.gvart.parleyroom.ai.service.Prompts
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic offline provider for tests and E2E. Reads the tagged sections of the prompt and
 * answers with plausible output derived from the notes that always passes validation.
 *
 * Markers anywhere in the first user message: `[fake:invalid-once]`, `[fake:invalid]`,
 * `[fake:error]`, `[fake:rate-limit]`, `[fake:delay=<ms>]`; sentence feedback also `[fake:wrong]`.
 */
class FakeLlmGateway : LlmGateway {

    override val modelId = "fake"

    data class Call(val system: String, val messages: List<LlmMessage>)

    override suspend fun complete(system: String, messages: List<LlmMessage>, maxTokens: Int): LlmReply {
        received += Call(system, messages)
        val request = messages.first().text
        val isRetry = Prompts.section(messages.last().text, Prompts.VALIDATION_ERRORS) != null

        Regex("""\[fake:delay=(\d+)]""").find(request)?.let { delay(it.groupValues[1].toLong()) }
        if ("[fake:error]" in request) throw LlmException("AI_PROVIDER_ERROR", "The AI provider request failed")
        if ("[fake:rate-limit]" in request) throw LlmException("AI_RATE_LIMITED", "The AI provider is rate limiting requests")
        val invalid = "[fake:invalid]" in request || ("[fake:invalid-once]" in request && !isRetry)

        val output = when (Prompts.section(request, "task")) {
            Prompts.TASK_FILL -> fill(request)
            Prompts.TASK_SUGGEST_TAGS -> suggestTags(request, invalid)
            Prompts.TASK_REFINE -> refine(request, invalid)
            Prompts.TASK_SENTENCE_FEEDBACK -> sentenceFeedback(request, invalid)
            else -> generate(request, invalid)
        }
        val text = output.toString()
        return LlmReply(text, inputTokens = (system.length + request.length) / 4, outputTokens = text.length / 4)
    }

    private fun generate(request: String, invalid: Boolean): JsonObject {
        val club = Prompts.section(request, "mode") == "CLUB"
        val level = Prompts.section(request, "context")?.lineSequence()
            ?.firstOrNull { it.startsWith("Level: ") }?.removePrefix("Level: ")?.takeIf { it.length == 2 }
        val vocab = vocabFromNotes(Prompts.section(request, "notes").orEmpty(), level)
        val first = vocab.firstOrNull()
        val noun = vocab.firstOrNull { it["wordType"]?.jsonPrimitive?.content == "NOUN" }

        val blocks = buildJsonArray {
            add(buildJsonObject { put("id", "b1"); put("type", "heading"); put("level", 1); put("text", "Nachbereitung") })
            add(buildJsonObject {
                put("id", "b2"); put("type", "vocab_table"); put("title", "Alltag")
                put("vocabKeys", JsonArray(vocab.map { it["key"]!! }))
            })
            if (club) {
                add(buildJsonObject {
                    put("id", "b3"); put("type", "grammar_box"); put("variant", "TIP"); put("title", "Tipp: Perfekt")
                    put("content", richText("Bewegung und Veränderung: Perfekt mit „sein“."))
                    putJsonArray("examples") { add(JsonPrimitive("Ich bin zu Hause geblieben.")) }
                })
                add(buildJsonObject {
                    put("id", "b4"); put("type", "free_sentences"); put("interactive", false); put("purpose", "SPEAKING")
                    put("instructions", "Sprecht über diese Fragen.")
                    putJsonArray("items") {
                        add(buildJsonObject { put("id", "b4i1"); put("prompt", "Was machst du gern im Haushalt?") })
                        add(buildJsonObject { put("id", "b4i2"); put("prompt", "Wer kümmert sich bei dir um die Blumen?") })
                    }
                })
            } else {
                val word = first?.get("lemma")?.jsonPrimitive?.content ?: "Deutsch"
                add(buildJsonObject {
                    put("id", "b3"); put("type", "gap_fill"); put("interactive", true)
                    put("instructions", "Ergänze die Sätze.")
                    putJsonArray("wordBox") { add(JsonPrimitive(word)) }
                    putJsonArray("items") {
                        add(buildJsonObject {
                            put("id", "b3i1")
                            put("text", if (invalid) "Heute lernen wir etwas." else "Heute lernen wir: ___.")
                            putJsonObject("solution") { putJsonArray("answers") { add(buildJsonArray { add(JsonPrimitive(word)) }) } }
                        })
                    }
                })
                add(articleQuestion(noun))
            }
        }
        return buildJsonObject {
            put("vocab", JsonArray(vocab))
            putJsonObject("document") { put("title", if (club) "Club – Überblick" else "Nachbereitung – Wortschatz und Übungen"); put("blocks", blocks) }
            putJsonArray("suggestedTopics") { add(buildJsonObject { put("name", "Alltag") }) }
            putJsonArray("suggestedGrammarTopics") {
                add(buildJsonObject { put("name", "Perfekt"); level?.let { put("level", it) } })
            }
            putJsonArray("correctedSentences") {}
        }
    }

    /** Keeps the current output (incl. manual edits) and appends a note with the instruction. */
    private fun refine(request: String, invalid: Boolean): JsonObject {
        val current = Json.parseToJsonElement(Prompts.section(request, "current_output")!!).jsonObject
        val document = current["document"]!!.jsonObject
        val instruction = Prompts.section(request, "refine_instruction").orEmpty()
        val blocks = document["blocks"]!!.jsonArray + buildJsonObject {
            put("id", "refined"); put("type", "rich_text")
            put("content", if (invalid) JsonPrimitive("not rich text") else richText("Überarbeitet: $instruction"))
        }
        val title = document["title"]!!.jsonPrimitive.content
        return JsonObject(current + ("document" to buildJsonObject {
            put("title", if (title.endsWith("(überarbeitet)")) title else "$title (überarbeitet)")
            put("blocks", JsonArray(blocks))
        }))
    }

    private fun fill(request: String): JsonObject {
        val entries = Json.parseToJsonElement(Prompts.section(request, "entries")!!).jsonArray
        return buildJsonObject {
            putJsonArray("entries") {
                entries.forEach { element ->
                    val entry = element.jsonObject
                    val lemma = entry["lemma"]!!.jsonPrimitive.content
                    val missing = entry["missing"]!!.jsonArray.map { it.jsonPrimitive.content }
                    add(buildJsonObject {
                        put("key", entry["key"]!!)
                        putJsonObject("translations") { missing.filter { it != "de_explanation" }.forEach { put(it, "$lemma ($it)") } }
                        if ("de_explanation" in missing) put("explanationDe", "Erklärung: $lemma")
                    })
                }
            }
        }
    }

    /** Level = first CEFR token, topics / grammar = library names found in the text, else Alltag / Perfekt. */
    private fun suggestTags(request: String, invalid: Boolean): JsonObject {
        val text = Prompts.section(request, "material_name").orEmpty() + "\n" + Prompts.section(request, "material_text").orEmpty()
        val lower = text.lowercase()
        val level = Regex("\\b(A1|A2|B1|B2|C1|C2)\\b").find(text)?.value
        val source = Prompts.section(request, "source")
        val topics = Prompts.section(request, "library_topics").orEmpty().lines()
            .filter { it.isNotBlank() && !it.startsWith("(") }
            .map { path -> path.split(" > ").map(String::trim) }
            .filter { parts -> parts.last().lowercase() in lower }
        val grammar = Prompts.section(request, "library_grammar_topics").orEmpty().lines()
            .filter { it.isNotBlank() && !it.startsWith("(") }
            .map { it.replace(Regex(" \\((A1|A2|B1|B2|C1|C2)\\)$"), "").trim() }
            .filter { it.lowercase() in lower }
        return buildJsonObject {
            level?.let { put("level", it) }
            if (source != "NAME_ONLY") put("skill", "READING")
            putJsonArray("topics") {
                if (invalid) add(buildJsonObject { put("name", " ") })
                else if (topics.isEmpty()) add(buildJsonObject { put("name", "Alltag") })
                else topics.forEach { parts ->
                    add(buildJsonObject { put("name", parts.last()); if (parts.size > 1) put("parentName", parts[parts.size - 2]) })
                }
            }
            putJsonArray("grammarTopics") {
                grammar.ifEmpty { listOf("Perfekt") }.forEach { add(buildJsonObject { put("name", it) }) }
            }
        }
    }

    /** Uses the word = the lemma stem occurs; correct = uses the word and no `[fake:wrong]` marker. */
    private fun sentenceFeedback(request: String, invalid: Boolean): JsonObject {
        val sentence = Prompts.section(request, "sentence").orEmpty()
        val lemma = Prompts.section(request, "target_word").orEmpty().lineSequence()
            .firstOrNull { it.startsWith("lemma: ") }?.removePrefix("lemma: ").orEmpty()
        val language = Prompts.section(request, "translation_language")
        val usesWord = lemmaStem(lemma).let { it.isNotEmpty() && it in sentence.lowercase() }
        val correct = usesWord && "[fake:wrong]" !in sentence
        val cleaned = Regex("""\[fake:[^\]]*]""").replace(sentence, "").trim().replace(WHITESPACE, " ")
        val corrected = if (correct) sentence
        else cleaned.ifEmpty { lemma }.replaceFirstChar(Char::uppercase).let { if (it.last() in ".!?") it else "$it." }
        val explanation = when {
            invalid -> ""
            correct -> "Richtig, gut gemacht!"
            !usesWord -> "Benutze das Wort „$lemma“ im Satz."
            else -> "Achte auf Großschreibung und Satzzeichen."
        }
        return buildJsonObject {
            put("isCorrect", correct)
            put("corrected", corrected)
            put("explanation", explanation)
            language?.let { put("explanationTranslation", "($it) $explanation") }
            put("usesWord", usesWord)
        }
    }

    private fun lemmaStem(lemma: String): String {
        val lower = lemma.trim().lowercase()
        return when {
            lower.length > 4 && lower.endsWith("en") -> lower.dropLast(2)
            lower.length > 3 && lower.endsWith("n") -> lower.dropLast(1)
            else -> lower
        }
    }

    private fun articleQuestion(noun: JsonObject?): JsonObject {
        val articles = listOf("DER", "DIE", "DAS")
        val correct = noun?.get("article")?.jsonPrimitive?.content ?: "DAS"
        return buildJsonObject {
            put("id", "b4"); put("type", "multiple_choice"); put("interactive", true)
            put("instructions", "Wähle den richtigen Artikel.")
            putJsonArray("items") {
                add(buildJsonObject {
                    put("id", "b4i1")
                    put("question", "___ ${noun?.get("lemma")?.jsonPrimitive?.content ?: "Wort"}")
                    putJsonArray("options") { articles.forEach { add(buildJsonObject { put("id", "o$it"); put("text", it.lowercase()) }) } }
                    putJsonObject("solution") { putJsonArray("correctOptionIds") { add(JsonPrimitive("o$correct")) } }
                })
            }
        }
    }

    private fun richText(text: String): JsonObject = buildJsonObject {
        put("type", "doc")
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "paragraph")
                putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
            })
        }
    }

    companion object {
        /** Every call, for tests (e.g. the privacy test inspects what the model would see). */
        val received: MutableList<Call> = CopyOnWriteArrayList()

        private val WHITESPACE = Regex("\\s+")

        /** One word per note line without digits: `der X` / capitalised word -> noun, `-en` -> verb, else phrase. */
        fun vocabFromNotes(notes: String, level: String?): List<JsonObject> {
            val seen = mutableSetOf<String>()
            return notes.lineSequence()
                .map { it.substringBefore("<-").trim() }
                .filter { it.isNotEmpty() && it.none(Char::isDigit) }
                .mapNotNull { line ->
                    val words = line.split(WHITESPACE)
                    val (lemma, type, article) = when {
                        words.size == 2 && words[0].lowercase() in setOf("der", "die", "das") ->
                            Triple(words[1].replaceFirstChar(Char::uppercase), "NOUN", words[0].uppercase())
                        words.size == 1 && words[0].first().isUpperCase() -> Triple(words[0], "NOUN", articleFor(words[0]))
                        words.size == 1 && words[0].endsWith("en") -> Triple(words[0], "VERB", null)
                        else -> Triple(line, "PHRASE", null)
                    }
                    if (!seen.add("${lemma.lowercase()}|$type|$article")) null
                    else Triple(lemma, type, article)
                }
                .take(30)
                .mapIndexed { i, (lemma, type, article) ->
                    buildJsonObject {
                        put("key", "v${i + 1}")
                        put("lemma", lemma)
                        article?.let { put("article", it) }
                        put("wordType", type)
                        putJsonObject("translations") { put("ru", "$lemma (ru)"); put("en", "$lemma (en)") }
                        put("explanationDe", "Erklärung: $lemma")
                        put("exampleSentence", "Beispiel mit $lemma.")
                        level?.let { put("level", it) }
                        put("topicName", "Alltag")
                    }
                }
                .toList()
        }

        private fun articleFor(noun: String): String = when {
            listOf("ung", "heit", "keit", "schaft", "e").any { noun.endsWith(it) } -> "DIE"
            listOf("chen", "lein", "ment").any { noun.endsWith(it) } -> "DAS"
            else -> "DER"
        }
    }
}

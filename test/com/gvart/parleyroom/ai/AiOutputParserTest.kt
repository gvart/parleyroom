package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.data.DraftMode
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.llm.LlmMessage
import com.gvart.parleyroom.ai.service.AiBlocks
import com.gvart.parleyroom.ai.service.AiOutputInvalid
import com.gvart.parleyroom.ai.service.AiOutputParser
import com.gvart.parleyroom.ai.service.DraftTarget
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiOutputParserTest {

    private fun fakeAnswer(mode: DraftMode, prompt: String = ""): String = runBlocking {
        val request = Prompts.generate(mode, "Level: B1", ANNA_NOTES, "", prompt)
        FakeLlmGateway().complete(Prompts.draftSystem(mode), listOf(LlmMessage.user(request)), 1000).text
    }

    private fun answer(): JsonObject = Json.parseToJsonElement(fakeAnswer(DraftMode.ONE_ON_ONE)).jsonObject

    private fun JsonObject.withHomework(key: String, value: kotlinx.serialization.json.JsonElement): JsonObject =
        JsonObject(this + ("homework" to JsonObject(this["homework"]!!.jsonObject + (key to value))))

    private fun issues(json: JsonObject, target: DraftTarget = DraftTarget.BUNDLE) =
        assertFailsWith<AiOutputInvalid> { AiOutputParser.parseDraft(json.toString(), target) }.issues

    @Test
    fun `generate and refine prompts explain the automatic exercise numbering`() {
        val generate = Prompts.generate(DraftMode.ONE_ON_ONE, "Level: B1", ANNA_NOTES, "", "")
        val refine = Prompts.refine(DraftMode.ONE_ON_ONE, DraftTarget.BUNDLE, "Level: B1", ANNA_NOTES, "", "", "{}", "Mach Übung 2 leichter")
        assertTrue(Prompts.exerciseNumbering in generate)
        assertTrue(Prompts.exerciseNumbering in refine)
        assertTrue("<target>\nbundle\n</target>" in refine)
        // Numbered types are real block types, and the structural blocks are not numbered.
        assertTrue(DocumentBlockValidator.BLOCK_TYPES.containsAll(Prompts.EXERCISE_BLOCK_TYPES))
        assertEquals(
            setOf("heading", "rich_text", "vocab_table", "grammar_box"),
            DocumentBlockValidator.BLOCK_TYPES - Prompts.EXERCISE_BLOCK_TYPES.toSet(),
        )
    }

    @Test
    fun `fake output passes the strict profile in both modes`() {
        val draft = AiOutputParser.parseDraft(fakeAnswer(DraftMode.ONE_ON_ONE), DraftTarget.BUNDLE)
        assertEquals(6, draft.words.size)
        val exercise = assertNotNull(draft.exerciseDocument)
        assertTrue(DocumentBlockValidator.completenessIssues(exercise.blocks).isEmpty())
        assertTrue(draft.tasks.size in AiOutputParser.MIN_TASKS..AiOutputParser.MAX_TASKS)
        assertEquals(listOf("Alltag"), draft.words.first().topics.map { it.name })

        val club = AiOutputParser.parseDraft(fakeAnswer(DraftMode.CLUB), DraftTarget.NOTES_DOCUMENT)
        val notes = assertNotNull(club.notesDocument)
        assertTrue(DocumentBlockValidator.completenessIssues(notes.blocks).isEmpty())
        assertTrue(club.words.isEmpty() && club.exerciseDocument == null && club.tasks.isEmpty())
    }

    @Test
    fun `short ids become uuids and option references follow`() {
        val blocks = AiOutputParser.parseDraft(fakeAnswer(DraftMode.ONE_ON_ONE), DraftTarget.BUNDLE).exerciseDocument!!.blocks
        val mc = blocks.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "multiple_choice" }
        val item = mc["items"]!!.jsonArray.single().jsonObject
        val optionIds = item["options"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val correct = item["solution"]!!.jsonObject["correctOptionIds"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(correct in optionIds)
        assertTrue(optionIds.all { it.length == 36 })
    }

    @Test
    fun `a refined document keeps vocab tables with the given keys and round-trips`() {
        val table = buildJsonObject {
            put("id", JsonPrimitive("b9")); put("type", JsonPrimitive("vocab_table")); put("title", JsonPrimitive("Haushalt"))
            put("vocabKeys", JsonArray(listOf(JsonPrimitive("w1"))))
        }
        val blocks = AiOutputParser.parseDraft(fakeAnswer(DraftMode.ONE_ON_ONE), DraftTarget.BUNDLE).exerciseDocument!!.blocks
        val ai = JsonArray(AiBlocks.toAi(blocks, emptyMap(), emptyMap()) + table)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}").find(ai.toString()) == null, "no uuids go to the model")
        val text = """{ "document": { "title": "Neu", "blocks": $ai } }"""

        val refined = AiOutputParser.parseDocumentRefine(text, setOf("w1"))
        DocumentBlockValidator.validate(refined.blocks)
        assertEquals(listOf("w1"), refined.vocabTables.values.single())
        assertNotEquals(blocks, JsonArray(refined.blocks.dropLast(1)), "fresh uuids")

        val unknown = assertFailsWith<AiOutputInvalid> { AiOutputParser.parseDocumentRefine(text, emptySet()) }.issues
        assertTrue(unknown.any { "unknown vocab key w1" in it.message })
    }

    @Test
    fun `problems are reported with pointers`() {
        val invalid = assertFailsWith<AiOutputInvalid> {
            AiOutputParser.parseDraft(fakeAnswer(DraftMode.ONE_ON_ONE, "[fake:invalid]"), DraftTarget.BUNDLE)
        }
        assertTrue(invalid.issues.any { it.pointer == "/homework/document/blocks/1/items/0/text" })

        assertFailsWith<AiOutputInvalid> { AiOutputParser.parseDraft("Sorry, I cannot help.", DraftTarget.BUNDLE) }
        val wrongShape = assertFailsWith<AiOutputInvalid> { AiOutputParser.parseDraft("""{ "words": 5 }""", DraftTarget.BUNDLE) }
        assertEquals("", wrongShape.issues.single().pointer)
        assertEquals("/homework", issues(buildJsonObject { put("words", JsonArray(emptyList())) }).single().pointer)

        val answer = answer()
        val words = answer["words"]!!.jsonArray
        val bad = JsonObject(answer + ("words" to JsonArray(words + words.first() + buildJsonObject {
            put("lemma", JsonPrimitive("gehen"))
            put("article", JsonPrimitive("DER"))
            put("wordType", JsonPrimitive("VERB"))
            put("translations", buildJsonObject { put("fr", JsonPrimitive("aller")) })
        })))
        val wordIssues = assertFailsWith<AiOutputInvalid> { AiOutputParser.parseDraft("```json\n$bad\n```", DraftTarget.BUNDLE) }.issues
        assertTrue(wordIssues.any { "listed twice" in it.message })
        assertTrue(wordIssues.any { "Only nouns can have an article" in it.message })
        assertTrue(wordIssues.any { it.pointer.endsWith("/translations") })
    }

    @Test
    fun `homework needs one answerable document without vocab tables and 1 to 3 tasks`() {
        val answer = answer()
        val tasks = answer["homework"]!!.jsonObject["tasks"]!!.jsonArray
        assertTrue(issues(answer.withHomework("tasks", JsonArray(emptyList()))).any { it.pointer == "/homework/tasks" })
        assertTrue(issues(answer.withHomework("tasks", JsonArray(tasks + tasks))).any { it.pointer == "/homework/tasks" })

        val document = answer["homework"]!!.jsonObject["document"]!!.jsonObject
        val onlyHeading = JsonObject(document + ("blocks" to JsonArray(document["blocks"]!!.jsonArray.take(1))))
        assertTrue(issues(answer.withHomework("document", onlyHeading)).any { "interactive" in it.message })

        val table = buildJsonObject {
            put("id", JsonPrimitive("t1")); put("type", JsonPrimitive("vocab_table")); put("vocabKeys", JsonArray(emptyList()))
        }
        val withTable = JsonObject(document + ("blocks" to JsonArray(document["blocks"]!!.jsonArray + table)))
        assertTrue(issues(answer.withHomework("document", withTable)).any { "vocab_table is not allowed" in it.message })
    }

    @Test
    fun `an item refine must answer with exactly that item`() {
        val answer = answer()
        val word = AiOutputParser.parseDraft(
            buildJsonObject { put("words", JsonArray(listOf(answer["words"]!!.jsonArray.first()))) }.toString(), DraftTarget.WORD,
        )
        assertEquals(1, word.words.size)
        assertTrue(issues(answer, DraftTarget.WORD).any { it.pointer == "/words" })
        assertTrue(issues(answer, DraftTarget.TASK).any { it.pointer == "/homework/tasks" })
        assertEquals("/notes", issues(answer, DraftTarget.NOTES_DOCUMENT).single().pointer)
    }

    @Test
    fun `the system prompt embeds the block schema`() {
        val system = Prompts.draftSystem(DraftMode.ONE_ON_ONE)
        assertTrue("\"vocab_table\"" in system && "{{BLOCK_SCHEMA}}" !in system && "{{BLOCK_RULES}}" !in system)
        assertTrue("# Club session" in Prompts.draftSystem(DraftMode.CLUB))
        assertTrue("\"gap_fill\"" in Prompts.documentRefineSystem)
    }
}

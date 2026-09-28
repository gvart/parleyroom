package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.llm.LlmMessage
import com.gvart.parleyroom.ai.service.AiBlocks
import com.gvart.parleyroom.ai.service.AiOutputInvalid
import com.gvart.parleyroom.ai.service.AiOutputParser
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.NachbereitungMode
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
import kotlin.test.assertTrue

class AiOutputParserTest {

    private fun fakeAnswer(mode: NachbereitungMode, prompt: String = ""): String = runBlocking {
        val request = Prompts.generate(mode, "Level: B1", ANNA_NOTES, prompt)
        FakeLlmGateway().complete(Prompts.nachbereitungSystem(mode), listOf(LlmMessage.user(request)), 1000).text
    }

    @Test
    fun `generate and refine prompts explain the automatic exercise numbering`() {
        val generate = Prompts.generate(NachbereitungMode.ONE_ON_ONE, "Level: B1", ANNA_NOTES, "")
        val refine = Prompts.refine(NachbereitungMode.ONE_ON_ONE, "Level: B1", ANNA_NOTES, "", "{}", "Mach Übung 2 leichter")
        assertTrue(Prompts.exerciseNumbering in generate)
        assertTrue(Prompts.exerciseNumbering in refine)
        // Numbered types are real block types, and the structural blocks are not numbered.
        assertTrue(DocumentBlockValidator.BLOCK_TYPES.containsAll(Prompts.EXERCISE_BLOCK_TYPES))
        assertEquals(
            setOf("heading", "rich_text", "vocab_table", "grammar_box"),
            DocumentBlockValidator.BLOCK_TYPES - Prompts.EXERCISE_BLOCK_TYPES.toSet(),
        )
    }

    @Test
    fun `fake output passes the strict profile in both modes`() {
        for (mode in NachbereitungMode.entries) {
            val parsed = AiOutputParser.parseGeneration(fakeAnswer(mode))
            assertTrue(DocumentBlockValidator.completenessIssues(parsed.blocks).isEmpty())
            assertEquals(parsed.output.vocab.map { it.key }, parsed.vocabTables.values.single())
        }
    }

    @Test
    fun `short ids become uuids and option references follow`() {
        val parsed = AiOutputParser.parseGeneration(fakeAnswer(NachbereitungMode.ONE_ON_ONE))
        val mc = parsed.blocks.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "multiple_choice" }
        val item = mc["items"]!!.jsonArray.single().jsonObject
        val optionIds = item["options"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val correct = item["solution"]!!.jsonObject["correctOptionIds"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(correct in optionIds)
        assertTrue(optionIds.all { it.length == 36 })
        val table = parsed.blocks.map { it.jsonObject }.single { it["type"]!!.jsonPrimitive.content == "vocab_table" }
        assertEquals(JsonArray(emptyList()), table["rows"])
    }

    @Test
    fun `toAi and fromAi round-trip a document`() {
        val parsed = AiOutputParser.parseGeneration(fakeAnswer(NachbereitungMode.ONE_ON_ONE))
        val tableId = parsed.vocabTables.keys.single()
        val ai = AiBlocks.toAi(parsed.blocks, emptyMap(), mapOf(tableId to listOf("v1", "v2")))
        assertTrue(ai.toString().contains("\"vocabKeys\":[\"v1\",\"v2\"]"))
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}").find(ai.toString()) == null, "no uuids go to the model")

        val back = AiBlocks.fromAi(ai)
        DocumentBlockValidator.validate(back.blocks)
        assertEquals(listOf("v1", "v2"), back.vocabTables.values.single())
        assertNotEquals(parsed.blocks, back.blocks, "fresh uuids")
    }

    @Test
    fun `problems are reported with pointers`() {
        val invalid = assertFailsWith<AiOutputInvalid> { AiOutputParser.parseGeneration(fakeAnswer(NachbereitungMode.ONE_ON_ONE, "[fake:invalid]")) }
        assertTrue(invalid.issues.any { it.pointer == "/document/blocks/2/items/0/text" })

        assertFailsWith<AiOutputInvalid> { AiOutputParser.parseGeneration("Sorry, I cannot help.") }
        val wrongShape = assertFailsWith<AiOutputInvalid> { AiOutputParser.parseGeneration("""{ "vocab": [] }""") }
        assertEquals("", wrongShape.issues.single().pointer)

        val answer = Json.parseToJsonElement(fakeAnswer(NachbereitungMode.ONE_ON_ONE)).jsonObject
        val vocab = answer["vocab"]!!.jsonArray
        val bad = JsonObject(answer + ("vocab" to JsonArray(vocab + buildJsonObject {
            put("key", JsonPrimitive("v1"))
            put("lemma", JsonPrimitive("gehen"))
            put("article", JsonPrimitive("DER"))
            put("wordType", JsonPrimitive("VERB"))
            put("translations", buildJsonObject { put("fr", JsonPrimitive("aller")) })
        })))
        val issues = assertFailsWith<AiOutputInvalid> { AiOutputParser.parseGeneration("```json\n$bad\n```") }.issues
        assertTrue(issues.any { "used twice" in it.message })
        assertTrue(issues.any { "Only nouns can have an article" in it.message })
        assertTrue(issues.any { it.pointer.endsWith("/translations") })
    }

    @Test
    fun `the system prompt embeds the block schema`() {
        val system = Prompts.nachbereitungSystem(NachbereitungMode.ONE_ON_ONE)
        assertTrue("\"vocab_table\"" in system && "{{BLOCK_SCHEMA}}" !in system)
        assertTrue("# Club session" in Prompts.nachbereitungSystem(NachbereitungMode.CLUB))
    }
}

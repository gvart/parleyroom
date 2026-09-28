package com.gvart.parleyroom.document

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentBlockValidatorTest {

    private fun blocks(json: String) = Json.parseToJsonElement(json) as JsonArray

    private fun assertInvalid(json: String, code: String, pointer: String) {
        val error = assertFailsWith<BadRequestException> { DocumentBlockValidator.validate(blocks(json)) }
        assertEquals(code, error.code, error.message)
        assertEquals(pointer, error.pointer, error.message)
    }

    @Test
    fun `every block type in the fixture is valid`() {
        val refs = DocumentBlockValidator.validate(blocks(DocumentFixtures.allBlocks))
        assertEquals(0, refs.vocabEntries.size)
    }

    @Test
    fun `unknown block type points at the type`() = assertInvalid(
        """[{"id":"$ID1","type":"crossword","interactive":true}]""",
        "DOCUMENT_INVALID_BLOCK", "/blocks/0/type",
    )

    @Test
    fun `unknown field is rejected`() = assertInvalid(
        """[{"id":"$ID1","type":"heading","text":"x","level":1,"color":"red"}]""",
        "DOCUMENT_INVALID_BLOCK", "/blocks/0/color",
    )

    @Test
    fun `gap count must match the answers`() = assertInvalid(
        """[{"id":"$ID1","type":"gap_fill","items":[{"id":"$ID2","text":"Ich ___ nach Hause.","solution":{"answers":[["gehe"],["x"]]}}]}]""",
        "DOCUMENT_INVALID_BLOCK", "/blocks/0/items/0/solution/answers",
    )

    @Test
    fun `correct option must reference an option`() = assertInvalid(
        """[{"id":"$ID1","type":"multiple_choice","items":[{"id":"$ID2","question":"?","options":[{"id":"$ID3","text":"a"},{"id":"$ID4","text":"b"}],"solution":{"correctOptionIds":["$ID5"]}}]}]""",
        "DOCUMENT_INVALID_BLOCK", "/blocks/0/items/0/solution/correctOptionIds/0",
    )

    @Test
    fun `ids must be unique within the document`() = assertInvalid(
        """[{"id":"$ID1","type":"heading","text":"a","level":1},{"id":"$ID1","type":"heading","text":"b","level":1}]""",
        "DOCUMENT_DUPLICATE_ID", "/blocks/1/id",
    )

    @Test
    fun `rich text rejects unknown nodes and unsafe links`() {
        assertInvalid(
            """[{"id":"$ID1","type":"rich_text","content":{"type":"doc","content":[{"type":"image","attrs":{"src":"x"}}]}}]""",
            "DOCUMENT_INVALID_BLOCK", "/blocks/0/content/content/0/type",
        )
        assertInvalid(
            """[{"id":"$ID1","type":"rich_text","content":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"x","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}]}}]""",
            "DOCUMENT_INVALID_BLOCK", "/blocks/0/content/content/0/content/0/marks/0/attrs/href",
        )
    }

    @Test
    fun `media needs exactly one source`() {
        val error = assertFailsWith<BadRequestException> {
            DocumentBlockValidator.validate(blocks("""[{"id":"$ID1","type":"media","kind":"AUDIO","questions":[]}]"""))
        }
        assertEquals("DOCUMENT_INVALID_BLOCK", error.code)
    }

    @Test
    fun `choice question solution shape follows its kind`() = assertInvalid(
        """[{"id":"$ID1","type":"reading","text":{"type":"doc"},"questions":[{"id":"$ID2","kind":"TRUE_FALSE","question":"?","solution":{"sampleAnswer":"x"}}]}]""",
        "DOCUMENT_INVALID_BLOCK", "/blocks/0/questions/0/solution/sampleAnswer",
    )

    private companion object {
        const val ID1 = "20000000-0000-0000-0000-000000000001"
        const val ID2 = "20000000-0000-0000-0000-000000000002"
        const val ID3 = "20000000-0000-0000-0000-000000000003"
        const val ID4 = "20000000-0000-0000-0000-000000000004"
        const val ID5 = "20000000-0000-0000-0000-000000000005"
    }
}

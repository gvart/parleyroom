package com.gvart.parleyroom.document

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.document.service.DocumentBlockValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.util.UUID
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
    fun `more answer groups than gaps is saved and reported as incomplete`() {
        val json = """[{"id":"$ID1","type":"gap_fill","items":[{"id":"$ID2","text":"Ich ___ nach Hause.","solution":{"answers":[["gehe"],["x"]]}}]}]"""
        DocumentBlockValidator.validate(blocks(json))
        assertEquals(
            listOf("/blocks/0/items/0/solution/answers"),
            DocumentBlockValidator.completenessIssues(blocks(json)).map { it.pointer },
        )
    }

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
    fun `media may not have both a url and a material`() {
        val error = assertFailsWith<BadRequestException> {
            DocumentBlockValidator.validate(
                blocks("""[{"id":"$ID1","type":"media","kind":"AUDIO","url":"https://x.org/a.mp3","materialId":"$ID2","questions":[]}]""")
            )
        }
        assertEquals("DOCUMENT_INVALID_BLOCK", error.code)
    }

    @Test
    fun `media can attach a file material`() {
        val references = DocumentBlockValidator.validate(
            blocks("""[{"id":"$ID1","type":"media","kind":"FILE","materialId":"$ID2","questions":[]}]""")
        )
        assertEquals(mapOf("/blocks/0/materialId" to UUID.fromString(ID2)), references.materials)
    }

    @Test
    fun `half-finished blocks are accepted on save`() {
        val halfFinished = """[
            {"id":"$ID1","type":"heading","text":"","level":1},
            {"id":"$ID2","type":"gap_fill","items":[{"id":"$ID3","text":"Ich ___ ___","solution":{"answers":[[""]]}}]},
            {"id":"$ID4","type":"multiple_choice","items":[{"id":"$ID5","question":"","options":[{"id":"$ID6","text":""}],"solution":{"correctOptionIds":[]}}]},
            {"id":"$ID7","type":"media","kind":"VIDEO","url":"","materialId":null,"questions":[{"id":"$ID8","kind":"CHOICE","question":""}]},
            {"id":"$ID9","type":"free_sentences","purpose":"SPEAKING","items":[]},
            {"id":"$ID10","type":"exam_part","exam":"","part":"","questions":[]},
            {"id":"$ID11","type":"rich_text","content":{"type":"doc","content":[{"type":"paragraph"}]}},
            {"id":"$ID12","type":"writing_task","items":[{"id":"$ID13","prompt":"","points":[""]}]}
        ]"""
        DocumentBlockValidator.validate(blocks(halfFinished))

        val issues = DocumentBlockValidator.completenessIssues(blocks(halfFinished)).map { it.pointer }.toSet()
        assertEquals(
            setOf(
                "/blocks/0/text",
                "/blocks/1/items/0/solution/answers",
                "/blocks/2/items/0/question", "/blocks/2/items/0/options", "/blocks/2/items/0/options/0/text",
                "/blocks/2/items/0/solution/correctOptionIds",
                "/blocks/3", "/blocks/3/questions/0/question", "/blocks/3/questions/0/options",
                "/blocks/3/questions/0/solution/correctOptionIds",
                "/blocks/4/items",
                "/blocks/5/exam", "/blocks/5/part",
                "/blocks/7/items/0/prompt", "/blocks/7/items/0/points/0",
            ),
            issues,
        )
    }

    @Test
    fun `the complete fixture has no completeness issues`() {
        assertEquals(emptyList(), DocumentBlockValidator.completenessIssues(blocks(DocumentFixtures.allBlocks)))
    }

    @Test
    fun `single choice with two correct options is saved and reported as incomplete`() {
        val json = """[{"id":"$ID1","type":"multiple_choice","items":[{"id":"$ID2","question":"?","multiple":false,"options":[{"id":"$ID3","text":"a"},{"id":"$ID4","text":"b"}],"solution":{"correctOptionIds":["$ID3","$ID4"]}}]}]"""
        DocumentBlockValidator.validate(blocks(json))
        assertEquals(
            listOf("/blocks/0/items/0/solution/correctOptionIds"),
            DocumentBlockValidator.completenessIssues(blocks(json)).map { it.pointer },
        )
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
        const val ID6 = "20000000-0000-0000-0000-000000000006"
        const val ID7 = "20000000-0000-0000-0000-000000000007"
        const val ID8 = "20000000-0000-0000-0000-000000000008"
        const val ID9 = "20000000-0000-0000-0000-000000000009"
        const val ID10 = "20000000-0000-0000-0000-000000000010"
        const val ID11 = "20000000-0000-0000-0000-000000000011"
        const val ID12 = "20000000-0000-0000-0000-000000000012"
        const val ID13 = "20000000-0000-0000-0000-000000000013"
    }
}

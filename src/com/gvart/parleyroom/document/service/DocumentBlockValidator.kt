package com.gvart.parleyroom.document.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import io.github.optimumcode.json.schema.ErrorCollector
import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Validates document blocks. [validate] is the structural check run on every save: the
 * published JSON Schema (`resources/document-blocks.schema.json`) plus the rules a schema
 * cannot express (unique ids, gap counts, option references). It accepts half-finished
 * blocks (empty texts and lists) because the editor autosaves while typing. Library
 * references (vocab entries, materials) are returned for the caller to check against the
 * owner's library. [completenessIssues] is the strict profile for finished content (AI output).
 */
object DocumentBlockValidator {

    const val MAX_BLOCKS_BYTES = 1024 * 1024
    private const val GAP = "___"

    val schemaText: String = DocumentBlockValidator::class.java.classLoader
        .getResource("document-blocks.schema.json")!!
        .readText()

    val schemaJson: JsonElement = Json.parseToJsonElement(schemaText)

    private val schema: JsonSchema = JsonSchema.fromDefinition(schemaText)

    data class References(
        /** JSON pointer -> vocab entry id of every vocab_table row. */
        val vocabEntries: Map<String, UUID>,
        /** JSON pointer -> material id of every media block that references a material. */
        val materials: Map<String, UUID>,
    )

    fun validate(blocks: JsonArray): References {
        if (blocks.toString().length > MAX_BLOCKS_BYTES)
            throw BadRequestException("Document is larger than 1 MiB", code = "DOCUMENT_TOO_LARGE")

        val errors = mutableListOf<ValidationError>()
        schema.validate(blocks, ErrorCollector { errors += it })
        if (errors.isNotEmpty()) {
            // The deepest error is the most specific one (if/then branches also report on the parent).
            val error = errors.maxBy { it.objectPath.toString().length }
            val pointer = "/blocks${error.objectPath}"
            val message = if (error.message.contains("false schema")) "field is not allowed here" else error.message
            throw invalid(pointer, message)
        }

        val seenIds = mutableSetOf<String>()
        val vocabEntries = mutableMapOf<String, UUID>()
        val materials = mutableMapOf<String, UUID>()
        blocks.forEachIndexed { index, element ->
            val block = element.jsonObject
            val pointer = "/blocks/$index"
            collectIds(block, pointer, seenIds)
            when (block.string("type")) {
                "gap_fill" -> checkGapFill(block, pointer)
                "multiple_choice" -> checkMultipleChoice(block, pointer)
                "reading", "exam_part" -> checkQuestions(block, pointer)
                "media" -> {
                    checkQuestions(block, pointer)
                    block.string("materialId")?.takeIf { it.isNotEmpty() }?.let { materials["$pointer/materialId"] = UUID.fromString(it) }
                }
                "vocab_table" -> block.array("rows").forEachIndexed { rowIndex, row ->
                    vocabEntries["$pointer/rows/$rowIndex/vocabEntryId"] = UUID.fromString(row.jsonObject.string("vocabEntryId"))
                }
            }
        }
        return References(vocabEntries, materials)
    }

    fun invalid(pointer: String, message: String) =
        BadRequestException("Invalid block at $pointer: $message", code = "DOCUMENT_INVALID_BLOCK", pointer = pointer)

    /** Block, item, option and row ids must be unique within a document. */
    private fun collectIds(block: JsonObject, pointer: String, seen: MutableSet<String>) {
        fun visit(value: JsonElement, path: String) {
            when (value) {
                is JsonObject -> {
                    (value["id"] as? JsonPrimitive)?.content?.lowercase()?.let { id ->
                        if (!seen.add(id))
                            throw BadRequestException(
                                "Id $id is used more than once in the document (at $path/id)",
                                code = "DOCUMENT_DUPLICATE_ID",
                                pointer = "$path/id",
                            )
                    }
                    // Solutions only reference ids (correctOptionIds), they never define one.
                    value.forEach { (key, child) -> if (key != "solution") visit(child, "$path/$key") }
                }
                is JsonArray -> value.forEachIndexed { i, child -> visit(child, "$path/$i") }
                else -> Unit
            }
        }
        visit(block, pointer)
    }

    private fun checkGapFill(block: JsonObject, pointer: String) {
        block.array("items").forEachIndexed { i, element ->
            val item = element.jsonObject
            val answers = item.solution()?.array("answers") ?: return@forEachIndexed
            val gaps = countGaps(item.string("text")!!)
            if (answers.size > gaps)
                throw invalid("$pointer/items/$i/solution/answers", "text has $gaps gap(s) but ${answers.size} answer group(s)")
        }
    }

    private fun countGaps(text: String): Int {
        var count = 0
        var index = text.indexOf(GAP)
        while (index >= 0) {
            count++
            // A longer run of underscores is still one gap.
            var end = index + GAP.length
            while (end < text.length && text[end] == '_') end++
            index = text.indexOf(GAP, end)
        }
        return count
    }

    private fun checkMultipleChoice(block: JsonObject, pointer: String) {
        block.array("items").forEachIndexed { i, element ->
            val item = element.jsonObject
            val itemPointer = "$pointer/items/$i"
            val correct = checkCorrectOptions(item, itemPointer) ?: return@forEachIndexed
            val multiple = (item["multiple"] as? JsonPrimitive)?.content == "true"
            if (!multiple && correct.size > 1)
                throw invalid("$itemPointer/solution/correctOptionIds", "single-choice item has more than one correct option")
        }
    }

    private fun checkQuestions(block: JsonObject, pointer: String) {
        block.array("questions").forEachIndexed { i, element ->
            checkCorrectOptions(element.jsonObject, "$pointer/questions/$i")
        }
    }

    /** Correct option ids must reference the item's own options. Returns them, or null without a solution. */
    private fun checkCorrectOptions(item: JsonObject, pointer: String): List<String>? {
        val correct = item.solution()?.get("correctOptionIds")?.jsonArray?.map { it.jsonPrimitive.content.lowercase() }
            ?: return null
        val optionIds = item["options"]?.jsonArray?.map { it.jsonObject.string("id")!!.lowercase() }.orEmpty().toSet()
        correct.forEachIndexed { j, id ->
            if (id !in optionIds) throw invalid("$pointer/solution/correctOptionIds/$j", "unknown option id $id")
        }
        return correct
    }

    data class Issue(val pointer: String, val message: String)

    /**
     * Strict "complete" profile for finished content (e.g. AI output): required texts are
     * non-empty, exercises have items, every gap has exactly one non-empty answer group,
     * media has a source, and choices have at least two options and a correct one.
     * Expects blocks that already passed [validate].
     */
    fun completenessIssues(blocks: JsonArray): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun requireText(obj: JsonObject, key: String, pointer: String) {
            if (obj.string(key).isNullOrBlank()) issues += Issue("$pointer/$key", "$key is empty")
        }
        fun requireItems(block: JsonObject, key: String, pointer: String): JsonArray {
            val items = block.array(key)
            if (items.isEmpty()) issues += Issue("$pointer/$key", "$key is empty")
            return items
        }
        fun checkOptions(item: JsonObject, pointer: String, multiple: Boolean) {
            val options = item.array("options")
            if (options.size < 2) issues += Issue("$pointer/options", "needs at least two options")
            options.forEachIndexed { j, option -> requireText(option.jsonObject, "text", "$pointer/options/$j") }
            val correct = item.solution()?.array("correctOptionIds").orEmpty()
            if (correct.isEmpty() || (!multiple && correct.size != 1))
                issues += Issue("$pointer/solution/correctOptionIds", if (multiple) "needs a correct option" else "needs exactly one correct option")
        }
        fun checkQuestions(block: JsonObject, pointer: String) {
            block.array("questions").forEachIndexed { i, element ->
                val question = element.jsonObject
                val questionPointer = "$pointer/questions/$i"
                requireText(question, "question", questionPointer)
                if (question.string("kind") == "CHOICE") checkOptions(question, questionPointer, multiple = true)
            }
        }

        blocks.forEachIndexed { index, element ->
            val block = element.jsonObject
            val pointer = "/blocks/$index"
            when (block.string("type")) {
                "heading" -> requireText(block, "text", pointer)
                "gap_fill" -> requireItems(block, "items", pointer).forEachIndexed { i, item ->
                    val itemPointer = "$pointer/items/$i"
                    val text = item.jsonObject.string("text").orEmpty()
                    val gaps = countGaps(text)
                    if (gaps == 0) issues += Issue("$itemPointer/text", "text has no gap (___)")
                    val answers = item.jsonObject.solution()?.array("answers").orEmpty()
                    if (answers.size != gaps || answers.any { group -> group.jsonArray.none { it.jsonPrimitive.content.isNotBlank() } })
                        issues += Issue("$itemPointer/solution/answers", "needs one non-empty answer group per gap ($gaps)")
                }
                "multiple_choice" -> requireItems(block, "items", pointer).forEachIndexed { i, item ->
                    val itemPointer = "$pointer/items/$i"
                    requireText(item.jsonObject, "question", itemPointer)
                    checkOptions(item.jsonObject, itemPointer, (item.jsonObject["multiple"] as? JsonPrimitive)?.content == "true")
                }
                "error_correction" -> requireItems(block, "items", pointer).forEachIndexed { i, item ->
                    requireText(item.jsonObject, "sentence", "$pointer/items/$i")
                }
                "free_sentences", "writing_task" -> requireItems(block, "items", pointer).forEachIndexed { i, item ->
                    requireText(item.jsonObject, "prompt", "$pointer/items/$i")
                    item.jsonObject.array("points").forEachIndexed { j, point ->
                        if (point.jsonPrimitive.content.isBlank()) issues += Issue("$pointer/items/$i/points/$j", "point is empty")
                    }
                }
                "free_form" -> block.array("items").forEachIndexed { i, item -> requireText(item.jsonObject, "prompt", "$pointer/items/$i") }
                "reading" -> checkQuestions(block, pointer)
                "media" -> {
                    if (block.string("url").isNullOrEmpty() && block.string("materialId").isNullOrEmpty())
                        issues += Issue(pointer, "media needs a url or a materialId")
                    checkQuestions(block, pointer)
                }
                "exam_part" -> {
                    requireText(block, "exam", pointer)
                    requireText(block, "part", pointer)
                    checkQuestions(block, pointer)
                }
            }
        }
        return issues
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
    private fun JsonObject.solution(): JsonObject? = this["solution"] as? JsonObject
}

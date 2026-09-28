package com.gvart.parleyroom.homework.service

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.text.Normalizer
import java.util.UUID

/** How a unit is answered. */
enum class AnswerShape { GAPS, OPTIONS, BOOL, TEXT, ITEM }

enum class UnitCheck { AUTO, REVIEW }

/** One answerable unit of an assignment: a document item/question, or a whole MATERIAL/TASK item. */
data class HomeworkUnit(
    val assignmentItemId: UUID,
    val blockId: UUID?,
    val itemRef: UUID?,
    val blockType: String?,
    val questionKind: String?,
    val shape: AnswerShape,
    val multiple: Boolean = false,
    val optionIds: Set<String> = emptySet(),
    val gapCount: Int = 0,
    val solution: JsonObject? = null,
    val responseType: HomeworkResponseType? = null,
) {
    val check: UnitCheck get() = if (shape == AnswerShape.GAPS || shape == AnswerShape.OPTIONS || shape == AnswerShape.BOOL) UnitCheck.AUTO else UnitCheck.REVIEW

    val key: UnitKey get() = UnitKey(assignmentItemId, blockId, itemRef)
}

data class UnitKey(val assignmentItemId: UUID, val blockId: UUID?, val itemRef: UUID?)

/** Everything an item contributes to unit derivation. */
data class ItemSource(
    val id: UUID,
    val kind: AssignmentItemKind,
    val responseType: HomeworkResponseType?,
    val blocks: JsonArray?,
)

data class CheckResult(
    val autoResult: AutoResult,
    val score: Double?,
    val caseMismatch: Boolean = false,
    val gapResults: List<String>? = null,
)

/**
 * Pure homework rules: which units an item has, what an answer for a unit must look like,
 * and the server-side auto-check of closed units.
 */
object HomeworkUnits {

    const val MAX_TEXT = 20_000
    const val MAX_GAP = 500
    const val MAX_UPLOADS_PER_UNIT = 5
    private const val GAP = "___"

    /** Exercise blocks whose `items` are answerable, by answer shape. */
    private val TEXT_ITEM_BLOCKS = setOf("error_correction", "writing_task", "free_form")
    private val QUESTION_BLOCKS = setOf("reading", "media", "exam_part")

    fun units(items: List<ItemSource>): List<HomeworkUnit> = items.flatMap(::units)

    fun units(item: ItemSource): List<HomeworkUnit> = when (item.kind) {
        AssignmentItemKind.DOCUMENT -> documentUnits(item.id, item.blocks ?: JsonArray(emptyList()))
        AssignmentItemKind.MATERIAL, AssignmentItemKind.TASK ->
            if (item.responseType == null) emptyList()
            else listOf(HomeworkUnit(item.id, null, null, null, null, AnswerShape.ITEM, responseType = item.responseType))
    }

    fun documentUnits(itemId: UUID, blocks: JsonArray): List<HomeworkUnit> = blocks.flatMap { element ->
        val block = element.jsonObject
        if ((block["interactive"] as? JsonPrimitive)?.booleanOrNull != true) return@flatMap emptyList()
        val type = block.string("type") ?: return@flatMap emptyList()
        val blockId = UUID.fromString(block.string("id"))
        fun unit(entry: JsonObject, shape: AnswerShape, kind: String? = null) = HomeworkUnit(
            assignmentItemId = itemId,
            blockId = blockId,
            itemRef = UUID.fromString(entry.string("id")),
            blockType = type,
            questionKind = kind,
            shape = shape,
            multiple = (entry["multiple"] as? JsonPrimitive)?.booleanOrNull == true,
            optionIds = entry.array("options").map { it.jsonObject.string("id")!!.lowercase() }.toSet(),
            gapCount = if (shape == AnswerShape.GAPS) countGaps(entry.string("text").orEmpty()) else 0,
            solution = entry["solution"] as? JsonObject,
        )
        when {
            type == "gap_fill" -> block.array("items").map { unit(it.jsonObject, AnswerShape.GAPS) }
            type == "multiple_choice" -> block.array("items").map { unit(it.jsonObject, AnswerShape.OPTIONS) }
            type in TEXT_ITEM_BLOCKS -> block.array("items").map { unit(it.jsonObject, AnswerShape.TEXT) }
            type == "free_sentences" && block.string("purpose") != "SPEAKING" ->
                block.array("items").map { unit(it.jsonObject, AnswerShape.TEXT) }
            type in QUESTION_BLOCKS -> block.array("questions").map { question ->
                val q = question.jsonObject
                val kind = q.string("kind")
                val shape = when (kind) {
                    "TRUE_FALSE" -> AnswerShape.BOOL
                    "CHOICE" -> AnswerShape.OPTIONS
                    else -> AnswerShape.TEXT
                }
                // CHOICE questions have no `multiple` flag: any number of options may be picked.
                unit(q, shape, kind).let { if (shape == AnswerShape.OPTIONS) it.copy(multiple = true) else it }
            }
            else -> emptyList()
        }
    }

    /**
     * Checks that [answer] fits [unit] (exact keys and types, limits, option ids).
     * Returns the upload ids an ITEM answer references, for the caller to verify.
     */
    fun validateAnswer(unit: HomeworkUnit, answer: JsonObject, pointer: String): List<UUID> {
        fun bad(path: String, message: String): Nothing =
            throw BadRequestException("Invalid answer at $pointer$path: $message", code = "HOMEWORK_ANSWER_INVALID", pointer = "$pointer$path")
        fun onlyKeys(vararg allowed: String) {
            answer.keys.firstOrNull { it !in allowed }?.let { bad("/$it", "field is not allowed here") }
        }
        fun text(key: String, max: Int, required: Boolean): String? {
            val value = answer[key]
            if (value == null || value is JsonNull) {
                if (required) bad("/$key", "$key is required")
                return null
            }
            if (value !is JsonPrimitive || !value.isString) bad("/$key", "must be a string")
            if (value.content.length > max) bad("/$key", "longer than $max characters")
            return value.content
        }
        fun stringArray(key: String): List<String> {
            val value = answer[key] as? JsonArray ?: bad("/$key", "must be an array")
            return value.mapIndexed { i, element ->
                val primitive = element as? JsonPrimitive
                if (primitive == null || !primitive.isString) bad("/$key/$i", "must be a string")
                primitive.content
            }
        }

        when (unit.shape) {
            AnswerShape.GAPS -> {
                onlyKeys("gaps")
                val gaps = stringArray("gaps")
                if (gaps.size > unit.gapCount) bad("/gaps", "more answers than gaps (${unit.gapCount})")
                gaps.forEachIndexed { i, gap -> if (gap.length > MAX_GAP) bad("/gaps/$i", "longer than $MAX_GAP characters") }
            }
            AnswerShape.OPTIONS -> {
                onlyKeys("optionIds")
                val ids = stringArray("optionIds").map { it.lowercase() }
                ids.forEachIndexed { i, id -> if (id !in unit.optionIds) bad("/optionIds/$i", "unknown option id $id") }
                if (ids.toSet().size != ids.size) bad("/optionIds", "option ids must be distinct")
                if (!unit.multiple && ids.size > 1) bad("/optionIds", "only one option may be chosen")
            }
            AnswerShape.BOOL -> {
                onlyKeys("isTrue")
                val value = answer["isTrue"] as? JsonPrimitive
                if (value == null || value.isString || value.booleanOrNull == null) bad("/isTrue", "must be a boolean")
            }
            AnswerShape.TEXT -> {
                onlyKeys("text")
                text("text", MAX_TEXT, required = true)
            }
            AnswerShape.ITEM -> {
                onlyKeys("text", "uploadIds")
                text("text", MAX_TEXT, required = false)
                if (answer["uploadIds"] == null || answer["uploadIds"] is JsonNull) return emptyList()
                if (unit.responseType == HomeworkResponseType.TEXT) bad("/uploadIds", "a TEXT task takes no uploads")
                val ids = stringArray("uploadIds")
                if (ids.size > MAX_UPLOADS_PER_UNIT) bad("/uploadIds", "at most $MAX_UPLOADS_PER_UNIT uploads")
                return ids.mapIndexed { i, id ->
                    runCatching { UUID.fromString(id) }.getOrElse { bad("/uploadIds/$i", "not a uuid") }
                }.distinct()
            }
        }
        return emptyList()
    }

    /** Whether the answer has any content (an empty text or gap list does not count as answered). */
    fun isAnswered(unit: HomeworkUnit, answer: JsonObject?): Boolean {
        if (answer == null) return false
        return when (unit.shape) {
            AnswerShape.GAPS -> answer.array("gaps").any { (it as JsonPrimitive).content.isNotBlank() }
            AnswerShape.OPTIONS -> answer.array("optionIds").isNotEmpty()
            AnswerShape.BOOL -> answer["isTrue"] != null
            AnswerShape.TEXT -> !answer.string("text").isNullOrBlank()
            AnswerShape.ITEM -> !answer.string("text").isNullOrBlank() || answer.array("uploadIds").isNotEmpty()
        }
    }

    /** Server-side auto-check of one unit, run on submit. */
    fun check(unit: HomeworkUnit, answer: JsonObject?): CheckResult {
        val answered = isAnswered(unit, answer)
        if (unit.check == UnitCheck.REVIEW)
            return if (answered) CheckResult(AutoResult.PENDING_REVIEW, null) else CheckResult(AutoResult.UNANSWERED, null)

        return when (unit.shape) {
            AnswerShape.GAPS -> checkGaps(unit, answer, answered)
            AnswerShape.OPTIONS -> {
                val correct = unit.solution?.get("correctOptionIds")?.jsonArray?.map { (it as JsonPrimitive).content.lowercase() }?.toSet()
                when {
                    correct.isNullOrEmpty() -> if (answered) CheckResult(AutoResult.PENDING_REVIEW, null) else CheckResult(AutoResult.UNANSWERED, null)
                    !answered -> incorrect()
                    else -> binary(answer!!.array("optionIds").map { (it as JsonPrimitive).content.lowercase() }.toSet() == correct)
                }
            }
            AnswerShape.BOOL -> {
                val expected = (unit.solution?.get("isTrue") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                when {
                    expected == null -> if (answered) CheckResult(AutoResult.PENDING_REVIEW, null) else CheckResult(AutoResult.UNANSWERED, null)
                    !answered -> incorrect()
                    else -> binary((answer!!["isTrue"] as JsonPrimitive).booleanOrNull == expected)
                }
            }
            else -> error("unreachable")
        }
    }

    private fun checkGaps(unit: HomeworkUnit, answer: JsonObject?, answered: Boolean): CheckResult {
        val groups = unit.solution?.get("answers")?.let { it as? JsonArray }
            ?.map { group -> group.jsonArray.map { normalize((it as JsonPrimitive).content) }.filter { it.isNotEmpty() } }
        val keyUsable = unit.gapCount > 0 && groups != null && groups.size == unit.gapCount && groups.all { it.isNotEmpty() }
        if (!keyUsable) return if (answered) CheckResult(AutoResult.PENDING_REVIEW, null) else CheckResult(AutoResult.UNANSWERED, null)
        if (!answered) return incorrect().copy(gapResults = List(unit.gapCount) { GAP_WRONG })

        val given = answer!!.array("gaps").map { normalize((it as JsonPrimitive).content) }
        val results = groups.mapIndexed { i, accepted ->
            val value = given.getOrNull(i).orEmpty()
            when {
                value.isEmpty() -> GAP_WRONG
                value in accepted -> GAP_CORRECT
                accepted.any { it.equals(value, ignoreCase = true) } -> GAP_CASE_MISMATCH
                else -> GAP_WRONG
            }
        }
        val correctCount = results.count { it != GAP_WRONG }
        return CheckResult(
            autoResult = if (correctCount == results.size) AutoResult.CORRECT else AutoResult.INCORRECT,
            score = correctCount.toDouble() / results.size,
            caseMismatch = GAP_CASE_MISMATCH in results,
            gapResults = results,
        )
    }

    /** Unicode NFC, trimmed, whitespace runs collapsed to one space. Case is kept. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).trim().replace(WHITESPACE, " ")

    fun countGaps(text: String): Int {
        var count = 0
        var index = text.indexOf(GAP)
        while (index >= 0) {
            count++
            var end = index + GAP.length
            while (end < text.length && text[end] == '_') end++
            index = text.indexOf(GAP, end)
        }
        return count
    }

    private fun binary(correct: Boolean) =
        CheckResult(if (correct) AutoResult.CORRECT else AutoResult.INCORRECT, if (correct) 1.0 else 0.0)

    private fun incorrect() = CheckResult(AutoResult.INCORRECT, 0.0)

    const val GAP_CORRECT = "CORRECT"
    const val GAP_CASE_MISMATCH = "CASE_MISMATCH"
    const val GAP_WRONG = "WRONG"
    private val WHITESPACE = Regex("\\s+")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
}

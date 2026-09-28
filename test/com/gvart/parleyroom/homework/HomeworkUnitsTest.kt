package com.gvart.parleyroom.homework

import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.homework.HomeworkFixtures.EC_1
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_1
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_2
import com.gvart.parleyroom.homework.HomeworkFixtures.MC_1
import com.gvart.parleyroom.homework.HomeworkFixtures.OPT_BIN
import com.gvart.parleyroom.homework.HomeworkFixtures.OPT_HABE
import com.gvart.parleyroom.homework.HomeworkFixtures.Q_OPEN
import com.gvart.parleyroom.homework.HomeworkFixtures.Q_TF
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.service.AnswerShape
import com.gvart.parleyroom.homework.service.HomeworkUnit
import com.gvart.parleyroom.homework.service.HomeworkUnits
import com.gvart.parleyroom.homework.service.ItemSource
import com.gvart.parleyroom.homework.service.UnitCheck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeworkUnitsTest {

    private val itemId = UUID.randomUUID()
    private val units = HomeworkUnits.documentUnits(itemId, Json.parseToJsonElement(HomeworkFixtures.EXERCISE_BLOCKS).jsonArray)

    private fun unit(ref: String) = units.single { it.itemRef.toString() == ref }
    private fun answer(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `only interactive exercise blocks become units, SPEAKING and context blocks do not`() {
        assertEquals(listOf(GAP_1, GAP_2, MC_1, Q_TF, Q_OPEN, EC_1), units.map { it.itemRef.toString() })
        assertEquals(listOf(UnitCheck.AUTO, UnitCheck.AUTO, UnitCheck.AUTO, UnitCheck.AUTO, UnitCheck.REVIEW, UnitCheck.REVIEW), units.map { it.check })
        assertEquals(2, unit(GAP_1).gapCount)
    }

    @Test
    fun `gap check is case-sensitive, accepts a case-only difference as caseMismatch`() {
        val exact = HomeworkUnits.check(unit(GAP_1), answer("""{"gaps":["musst","Wäsche"]}"""))
        assertEquals(AutoResult.CORRECT, exact.autoResult)
        assertFalse(exact.caseMismatch)
        assertEquals(1.0, exact.score)

        val lower = HomeworkUnits.check(unit(GAP_1), answer("""{"gaps":["musst","wäsche"]}"""))
        assertEquals(AutoResult.CORRECT, lower.autoResult)
        assertTrue(lower.caseMismatch)
        assertEquals(listOf("CORRECT", "CASE_MISMATCH"), lower.gapResults)
    }

    @Test
    fun `gap check normalises whitespace and NFC, scores partial answers`() {
        // "Wäsche" is the decomposed form of "Wäsche".
        val spaced = HomeworkUnits.check(unit(GAP_1), answer("""{"gaps":["  musst ","Wäsche"]}"""))
        assertEquals(AutoResult.CORRECT, spaced.autoResult)

        val half = HomeworkUnits.check(unit(GAP_1), answer("""{"gaps":["muss","Wäsche"]}"""))
        assertEquals(AutoResult.INCORRECT, half.autoResult)
        assertEquals(0.5, half.score)
        assertEquals(listOf("WRONG", "CORRECT"), half.gapResults)

        val alternative = HomeworkUnits.check(unit(GAP_2), answer("""{"gaps":["läuft"]}"""))
        assertEquals(AutoResult.CORRECT, alternative.autoResult)
    }

    @Test
    fun `a gap key that does not match the text goes to review`() {
        val broken = unit(GAP_1).copy(solution = answer("""{"answers":[["musst"]]}"""))
        assertEquals(AutoResult.PENDING_REVIEW, HomeworkUnits.check(broken, answer("""{"gaps":["musst","Wäsche"]}""")).autoResult)
        assertEquals(AutoResult.UNANSWERED, HomeworkUnits.check(broken, null).autoResult)
    }

    @Test
    fun `multiple choice is set equality, true-false exact, unanswered closed units are incorrect`() {
        assertEquals(AutoResult.CORRECT, HomeworkUnits.check(unit(MC_1), answer("""{"optionIds":["$OPT_BIN"]}""")).autoResult)
        assertEquals(AutoResult.INCORRECT, HomeworkUnits.check(unit(MC_1), answer("""{"optionIds":["$OPT_HABE"]}""")).autoResult)
        assertEquals(AutoResult.CORRECT, HomeworkUnits.check(unit(Q_TF), answer("""{"isTrue":true}""")).autoResult)
        assertEquals(AutoResult.INCORRECT, HomeworkUnits.check(unit(Q_TF), answer("""{"isTrue":false}""")).autoResult)
        assertEquals(AutoResult.INCORRECT, HomeworkUnits.check(unit(MC_1), null).autoResult)
        assertEquals(AutoResult.INCORRECT, HomeworkUnits.check(unit(GAP_1), answer("""{"gaps":["",""]}""")).autoResult)
    }

    @Test
    fun `open units are pending review when answered, unanswered otherwise`() {
        assertEquals(AutoResult.PENDING_REVIEW, HomeworkUnits.check(unit(EC_1), answer("""{"text":"Darum musst du dich kümmern"}""")).autoResult)
        assertEquals(AutoResult.UNANSWERED, HomeworkUnits.check(unit(Q_OPEN), answer("""{"text":"  "}""")).autoResult)
        assertNull(HomeworkUnits.check(unit(Q_OPEN), null).score)
    }

    @Test
    fun `answers must fit the unit`() {
        fun invalid(unit: HomeworkUnit, json: String): String? =
            assertFailsWith<BadRequestException> { HomeworkUnits.validateAnswer(unit, answer(json), "/a") }
                .also { assertEquals("HOMEWORK_ANSWER_INVALID", it.code) }.pointer

        assertEquals("/a/gaps", invalid(unit(GAP_1), """{"gaps":["a","b","c"]}"""))
        assertEquals("/a/text", invalid(unit(GAP_1), """{"gaps":["a"],"text":"x"}"""))
        assertEquals("/a/optionIds/0", invalid(unit(MC_1), """{"optionIds":["${UUID.randomUUID()}"]}"""))
        assertEquals("/a/optionIds", invalid(unit(MC_1), """{"optionIds":["$OPT_BIN","$OPT_HABE"]}"""))
        assertEquals("/a/isTrue", invalid(unit(Q_TF), """{"isTrue":"yes"}"""))
        assertEquals("/a/text", invalid(unit(EC_1), """{"text":42}"""))

        val textTask = HomeworkUnits.units(ItemSource(itemId, AssignmentItemKind.TASK, HomeworkResponseType.TEXT, null)).single()
        assertEquals(AnswerShape.ITEM, textTask.shape)
        assertEquals("/a/uploadIds", invalid(textTask, """{"uploadIds":["${UUID.randomUUID()}"]}"""))

        val audio = textTask.copy(responseType = HomeworkResponseType.AUDIO)
        val id = UUID.randomUUID()
        assertEquals(listOf(id), HomeworkUnits.validateAnswer(audio, answer("""{"uploadIds":["$id"]}"""), "/a"))
    }

    @Test
    fun `material without responseType has no unit`() {
        assertTrue(HomeworkUnits.units(ItemSource(itemId, AssignmentItemKind.MATERIAL, null, null)).isEmpty())
        assertTrue(HomeworkUnits.documentUnits(itemId, JsonArray(listOf(JsonObject(emptyMap())))).isEmpty())
    }
}

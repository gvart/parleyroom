package com.gvart.parleyroom.progress

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.STUDENT_2
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.generateAndWait
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.ai.transfer.GrammarGaps
import com.gvart.parleyroom.ai.transfer.NachbereitungState
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.library.LibraryFixtures
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressAiContextIntegrationTest : IntegrationTest() {

    /** The two gap lines of the model-facing context. */
    private fun gapLines(prompt: String): Pair<String, String> {
        val lines = prompt.lines()
        val needs = lines[lines.indexOfFirst { it.contains("still needs to work on") } + 1]
        val notCovered = lines[lines.indexOfFirst { it.contains("not covered yet") } + 1]
        return needs to notCovered
    }

    @Test
    fun `1-1 context lists the student's needs-work and not-covered grammar and nothing personal`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val token = getTeacherToken(client)
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val weak = LibraryFixtures.grammar("Konjunktiv II", LanguageLevel.B1, position = 0)
        LibraryFixtures.grammar("Passiv", LanguageLevel.B1, position = 1)
        val covered = LibraryFixtures.grammar("Relativsätze", LanguageLevel.B1, position = 2)
        val overridden = LibraryFixtures.grammar("Genitiv", LanguageLevel.B1, position = 3)
        LibraryFixtures.grammar("Perfekt", LanguageLevel.A2)
        ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("Doc", grammar = listOf(weak)), units = listOf(WRONG, WRONG, RIGHT))
        LibraryFixtures.tagLesson(seedLesson(), grammar = listOf(covered))
        // The teacher's override counts: Genitiv is not NOT_COVERED any more.
        client.put("/api/v1/students/$STUDENT/progress/grammar/$overridden/override") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{ "status": "COVERED", "note": "Olga kann das" }""")
        }
        val lessonId = seedLesson()

        val state = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>()
        assertEquals(GrammarGaps(listOf("Konjunktiv II"), listOf("Passiv"), 1, 1), state.context.grammarGaps)

        FakeLlmGateway.received.clear()
        assertEquals(GenerationJobStatus.SUCCEEDED, client.generateAndWait(token, lessonId).status)
        val call = FakeLlmGateway.received.single()
        val user = call.messages.joinToString("\n") { it.text }
        val (needs, notCovered) = gapLines(user)
        assertEquals("Konjunktiv II", needs)
        assertEquals("Passiv", notCovered)
        assertTrue("grammar gaps" in call.system, "the system prompt explains the gaps")
        val sent = call.system + user
        listOf("Olga kann das", "@test.com", STUDENT_ID, TEACHER_ID, weak.toString(), "1/3")
            .forEach { assertFalse(it in user, "'$it' must not be sent to the model") }
        assertNull(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").find(user), "no uuid")
        assertTrue(sent.isNotEmpty())
    }

    @Test
    fun `club context lists topics that need work for at least half of the attendees`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val token = getTeacherToken(client)
        val third = ProgressFixtures.linkedStudent("s3@test.com")
        val attendees = listOf(STUDENT, STUDENT_2, third)
        val majority = LibraryFixtures.grammar("Mehrheit schwach", LanguageLevel.B1, position = 0)
        val single = LibraryFixtures.grammar("Einer schwach", LanguageLevel.B1, position = 1)
        val coveredByTwo = LibraryFixtures.grammar("Zwei behandelt", LanguageLevel.B1, position = 2)
        LibraryFixtures.grammar("Niemand", LanguageLevel.B1, position = 3)
        fun weakOn(student: UUID, g: UUID) =
            ProgressFixtures.homework(student, documentId = LibraryFixtures.document("D$student$g", grammar = listOf(g)), units = listOf(WRONG, WRONG, WRONG))
        weakOn(STUDENT, majority); weakOn(STUDENT_2, majority)
        weakOn(third, single)
        LibraryFixtures.tagLesson(seedLesson(students = listOf(STUDENT, STUDENT_2)), grammar = listOf(coveredByTwo))

        val lessonId = seedLesson(type = LessonType.SPEAKING_CLUB, students = attendees, level = LanguageLevel.B1)
        val gaps = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(token) }.body<NachbereitungState>().context.grammarGaps
        assertEquals(listOf("Mehrheit schwach"), gaps.needsWork)
        // "Mehrheit schwach" is NEEDS_WORK for 2, NOT_COVERED for 1; "Einer schwach" NOT_COVERED for 2;
        // "Zwei behandelt" NOT_COVERED only for 1; "Niemand" for all 3.
        assertEquals(listOf("Einer schwach", "Niemand"), gaps.notCovered)

        FakeLlmGateway.received.clear()
        client.generateAndWait(token, lessonId)
        val user = FakeLlmGateway.received.single().messages.joinToString("\n") { it.text }
        assertTrue("Grammar the group still needs to work on" in user)
        assertEquals("Mehrheit schwach" to "Einer schwach, Niemand", gapLines(user))
    }

    @Test
    fun `lists are capped at 30 names but the counts are not`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        (0 until 35).forEach { LibraryFixtures.grammar("Thema %02d".format(it), LanguageLevel.B1, position = it) }
        val lessonId = seedLesson()
        val gaps = client.get("/api/v1/lessons/$lessonId/nachbereitung") { bearerAuth(getTeacherToken(client)) }
            .body<NachbereitungState>().context.grammarGaps
        assertEquals(30, gaps.notCovered.size)
        assertEquals(35, gaps.notCoveredCount)
        assertEquals("Thema 00", gaps.notCovered.first())
        assertEquals(0, gaps.needsWorkCount)
    }
}

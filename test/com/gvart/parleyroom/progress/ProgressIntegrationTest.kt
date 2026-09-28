package com.gvart.parleyroom.progress

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.STUDENT_2
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.progress.data.GrammarProgressOverrideTable
import com.gvart.parleyroom.progress.service.ProgressCalculator
import com.gvart.parleyroom.progress.service.ProgressConfig
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.progressRaw(token: String, studentId: UUID = STUDENT, query: String = ""): HttpResponse =
        get("/api/v1/students/$studentId/progress$query") { bearerAuth(token) }

    private suspend fun HttpClient.progress(token: String, studentId: UUID = STUDENT, query: String = ""): JsonObject {
        val response = progressRaw(token, studentId, query)
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private fun JsonObject.items() = this["grammar"]!!.jsonArray.flatMap { it.jsonObject["items"]!!.jsonArray }.map { it.jsonObject }
    private fun JsonObject.item(name: String) = items().single { it["name"]!!.jsonPrimitive.content == name }
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    private suspend fun HttpClient.putOverride(token: String, grammarId: UUID, body: String, studentId: UUID = STUDENT): HttpResponse =
        put("/api/v1/students/$studentId/progress/grammar/$grammarId/override") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }

    @Test
    fun `derived grammar statuses follow lessons, submitted homework and reviewed scores`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val none = LibraryFixtures.grammar("Nichts", LanguageLevel.B1, position = 0)
        val lesson = LibraryFixtures.grammar("Lektion", LanguageLevel.B1, position = 1)
        val practiced = LibraryFixtures.grammar("Geübt", LanguageLevel.B1, position = 2)
        val weak = LibraryFixtures.grammar("Schwach", LanguageLevel.B1, position = 3)
        val material = LibraryFixtures.grammar("Material", LanguageLevel.B1, position = 4)

        val held = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(3))
        LibraryFixtures.tagLesson(held, grammar = listOf(lesson, weak))

        // Submitted but not reviewed: PRACTICED; its wrong answers are not scored yet.
        val practicedDoc = LibraryFixtures.document("Übung", grammar = listOf(practiced))
        ProgressFixtures.homework(STUDENT, documentId = practicedDoc, status = HomeworkStatus.SUBMITTED, units = listOf(WRONG, WRONG, WRONG))
        // Reviewed: 1 of 3 correct -> NEEDS_WORK (wins over COVERED + PRACTICED).
        val weakDoc = LibraryFixtures.document("Schwach-Übung", grammar = listOf(weak))
        ProgressFixtures.homework(STUDENT, documentId = weakDoc, units = listOf(RIGHT, WRONG, WRONG))
        // MATERIAL item tagged with the grammar topic.
        ProgressFixtures.homework(STUDENT, materialId = LibraryFixtures.material("Video", grammar = listOf(material)))

        val body = client.progress(getTeacherToken(client))
        assertEquals("NOT_COVERED", body.item("Nichts").str("derived"))
        assertEquals("COVERED", body.item("Lektion").str("derived"))
        assertEquals("PRACTICED", body.item("Geübt").str("derived"))
        assertEquals("NEEDS_WORK", body.item("Schwach").str("derived"))
        assertEquals("PRACTICED", body.item("Material").str("derived"))
        assertEquals("NEEDS_WORK", body.item("Schwach").str("effective"))
        assertEquals(JsonNull, body.item("Schwach")["override"])

        val weakEvidence = body.item("Schwach").obj("evidence")
        assertEquals(1, weakEvidence["lessonCount"]!!.jsonPrimitive.int)
        assertTrue(weakEvidence["lastLessonAt"] !is JsonNull)
        assertEquals(1, weakEvidence.obj("homeworkScored")["correct"]!!.jsonPrimitive.int)
        assertEquals(3, weakEvidence.obj("homeworkScored")["total"]!!.jsonPrimitive.int)
        assertTrue(weakEvidence["lastPracticedAt"] !is JsonNull)
        val practicedEvidence = body.item("Geübt").obj("evidence")
        assertEquals(0, practicedEvidence.obj("homeworkScored")["total"]!!.jsonPrimitive.int, "SUBMITTED is not scored")
        assertEquals(0, body.item("Nichts").obj("evidence")["lessonCount"]!!.jsonPrimitive.int)
        assertEquals(listOf("Nichts", "Lektion", "Geübt", "Schwach", "Material"), body.items().map { it.str("name") }, "checklist order")
        assertEquals(practiced.toString(), body.item("Geübt").str("id"))
        assertEquals(none.toString(), body.item("Nichts").str("id"))
    }

    @Test
    fun `lessons count only when they took place for a confirmed participant`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val future = LibraryFixtures.grammar("Zukunft", LanguageLevel.B1)
        val cancelled = LibraryFixtures.grammar("Abgesagt", LanguageLevel.B1)
        val pending = LibraryFixtures.grammar("Angefragt", LanguageLevel.B1)
        val futureLesson = seedLesson(scheduledAt = OffsetDateTime.now().plusDays(2))
        LibraryFixtures.tagLesson(futureLesson, grammar = listOf(future))
        val cancelledLesson = seedLesson()
        LibraryFixtures.tagLesson(cancelledLesson, grammar = listOf(cancelled))
        val pendingLesson = seedLesson()
        LibraryFixtures.tagLesson(pendingLesson, grammar = listOf(pending))
        transaction {
            LessonTable.update({ LessonTable.id eq futureLesson }) { it[status] = LessonStatus.CONFIRMED }
            LessonTable.update({ LessonTable.id eq cancelledLesson }) { it[status] = LessonStatus.CANCELLED }
            LessonStudentTable.update({ LessonStudentTable.lessonId eq pendingLesson }) { it[status] = LessonStudentStatus.REQUESTED }
        }
        // OPEN homework never submitted: not practiced.
        val doc = LibraryFixtures.document("Offen", grammar = listOf(future))
        ProgressFixtures.homework(STUDENT, documentId = doc, status = HomeworkStatus.OPEN, attempt = 0, submittedAt = null)

        val body = client.progress(getTeacherToken(client))
        body.items().forEach { assertEquals("NOT_COVERED", it.str("derived"), it.str("name")) }
    }

    @Test
    fun `lessons started or completed before their scheduled time count at once`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val completed = LibraryFixtures.grammar("Früh fertig", LanguageLevel.B1)
        val running = LibraryFixtures.grammar("Läuft", LanguageLevel.B1)
        val requested = LibraryFixtures.grammar("Nur angefragt", LanguageLevel.B1)
        val tomorrow = OffsetDateTime.now().plusDays(1)
        val completedLesson = seedLesson(scheduledAt = tomorrow)          // seeded COMPLETED
        val runningLesson = seedLesson(scheduledAt = tomorrow)
        val requestedLesson = seedLesson(scheduledAt = tomorrow)
        LibraryFixtures.tagLesson(completedLesson, grammar = listOf(completed))
        LibraryFixtures.tagLesson(runningLesson, grammar = listOf(running))
        LibraryFixtures.tagLesson(requestedLesson, grammar = listOf(requested))
        transaction {
            LessonTable.update({ LessonTable.id eq runningLesson }) { it[status] = LessonStatus.IN_PROGRESS }
            LessonStudentTable.update({ LessonStudentTable.lessonId eq requestedLesson }) { it[status] = LessonStudentStatus.REQUESTED }
        }

        val body = client.progress(getTeacherToken(client))
        assertEquals("COVERED", body.item("Früh fertig").str("derived"))
        assertEquals(1, body.item("Früh fertig").obj("evidence")["lessonCount"]!!.jsonPrimitive.int)
        assertEquals("COVERED", body.item("Läuft").str("derived"))
        assertEquals("NOT_COVERED", body.item("Nur angefragt").str("derived"), "the participant must be CONFIRMED")
    }

    @Test
    fun `needs work needs the minimum number of scored items and a share below the threshold`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        fun topicWith(name: String, vararg units: HwUnit) {
            val g = LibraryFixtures.grammar(name, LanguageLevel.B1)
            ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("Doc $name", grammar = listOf(g)), units = units.toList())
        }
        topicWith("ZweiFalsch", WRONG, WRONG)                                   // 0/2: below minimum -> PRACTICED
        topicWith("ZweiVonDrei", RIGHT, RIGHT, WRONG)                           // 67 % -> PRACTICED
        topicWith("GenauSechzig", RIGHT, RIGHT, RIGHT, WRONG, WRONG)            // 60 % is not < 60 % -> PRACTICED
        topicWith("Lehrerurteil", HwUnit(AutoResult.INCORRECT, teacher = true), HwUnit(AutoResult.INCORRECT, teacher = true), WRONG)  // 2/3
        topicWith("Offen", HwUnit(AutoResult.PENDING_REVIEW), HwUnit(AutoResult.PENDING_REVIEW), HwUnit(AutoResult.PENDING_REVIEW), WRONG) // 0/1
        topicWith("LehrerFalsch", HwUnit(AutoResult.CORRECT, teacher = false), HwUnit(AutoResult.PENDING_REVIEW, teacher = false), RIGHT) // 1/3

        val body = client.progress(getTeacherToken(client))
        assertEquals("PRACTICED", body.item("ZweiFalsch").str("derived"))
        assertEquals("PRACTICED", body.item("ZweiVonDrei").str("derived"))
        assertEquals("PRACTICED", body.item("GenauSechzig").str("derived"))
        assertEquals("PRACTICED", body.item("Lehrerurteil").str("derived"))
        assertEquals(2, body.item("Lehrerurteil").obj("evidence").obj("homeworkScored")["correct"]!!.jsonPrimitive.int)
        assertEquals("PRACTICED", body.item("Offen").str("derived"))
        assertEquals(1, body.item("Offen").obj("evidence").obj("homeworkScored")["total"]!!.jsonPrimitive.int, "pending units are not scored")
        assertEquals("NEEDS_WORK", body.item("LehrerFalsch").str("derived"))
        val thresholds = body.obj("thresholds")
        assertEquals(0.6, thresholds["needsWorkBelow"]!!.jsonPrimitive.double)
        assertEquals(3, thresholds["minScoredItems"]!!.jsonPrimitive.int)
    }

    @Test
    fun `thresholds come from config`() = testApp(mapOf("progress.needs_work_below" to "0.8", "progress.min_scored_items" to "2")) {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val g = LibraryFixtures.grammar("Streng", LanguageLevel.B1)
        ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("Doc", grammar = listOf(g)), units = listOf(RIGHT, RIGHT, WRONG))
        val body = client.progress(getTeacherToken(client))
        assertEquals("NEEDS_WORK", body.item("Streng").str("derived"), "67 % < 80 %")
        assertEquals(2, body.obj("thresholds")["minScoredItems"]!!.jsonPrimitive.int)
    }

    @Test
    fun `teacher override wins over the derived status and the note is teacher-only`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val g = LibraryFixtures.grammar("Konjunktiv II", LanguageLevel.B1)
        ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("Doc", grammar = listOf(g)), units = listOf(WRONG, WRONG, WRONG))
        val teacher = getTeacherToken(client)

        val set = client.putOverride(teacher, g, """{ "status": "PRACTICED", "note": "  im Unterricht sicher  " }""")
        assertEquals(HttpStatusCode.OK, set.status)
        val item = set.body<JsonObject>()
        assertEquals("NEEDS_WORK", item.str("derived"))
        assertEquals("PRACTICED", item.str("effective"))
        assertEquals("im Unterricht sicher", item.obj("override").str("note"))
        assertEquals(TEACHER.toString(), item.obj("override").str("updatedBy"))

        // Upsert: a second PUT replaces status and note.
        val again = client.putOverride(teacher, g, """{ "status": "COVERED" }""").body<JsonObject>()
        assertEquals("COVERED", again.str("effective"))
        assertEquals(JsonNull, again.obj("override")["note"])
        client.putOverride(teacher, g, """{ "status": "PRACTICED", "note": "geheim" }""")

        val teacherView = client.progress(teacher).item("Konjunktiv II")
        assertEquals("PRACTICED", teacherView.str("effective"))
        assertEquals("geheim", teacherView.obj("override").str("note"))
        assertEquals(1, client.progress(teacher).obj("summary").obj("grammar")["practiced"]!!.jsonPrimitive.int, "summary uses effective")

        val studentView = client.progress(getStudentToken(client)).item("Konjunktiv II")
        assertEquals("PRACTICED", studentView.str("effective"))
        assertEquals("NEEDS_WORK", studentView.str("derived"))
        assertEquals(JsonNull, studentView.obj("override")["note"], "students never see the note")

        val deleted = client.delete("/api/v1/students/$STUDENT/progress/grammar/$g/override") { bearerAuth(teacher) }
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertEquals("NEEDS_WORK", deleted.body<JsonObject>().str("effective"))
        assertEquals(JsonNull, deleted.body<JsonObject>()["override"])
        // Deleting again is a no-op.
        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/students/$STUDENT/progress/grammar/$g/override") { bearerAuth(teacher) }.status)
    }

    @Test
    fun `override validation and access`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val g = LibraryFixtures.grammar("Passiv", LanguageLevel.B1)
        val teacher = getTeacherToken(client)

        suspend fun expect400(body: String, pointer: String) {
            val response = client.putOverride(teacher, g, body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            val problem = response.body<JsonObject>()
            assertEquals("GRAMMAR_OVERRIDE_INVALID", problem.str("code"))
            assertEquals(pointer, problem.str("pointer"))
        }
        expect400("""{ "status": "MASTERED" }""", "/status")
        expect400("""{ }""", "/status")
        expect400("""{ "status": "covered" }""", "/status")
        expect400("""{ "status": "COVERED", "note": "${"x".repeat(2001)}" }""", "/note")

        assertEquals(HttpStatusCode.Forbidden, client.putOverride(getStudentToken(client), g, """{ "status": "COVERED" }""").status)
        assertEquals(HttpStatusCode.Forbidden, client.putOverride(getAdminToken(client), g, """{ "status": "COVERED" }""").status)
        assertEquals(HttpStatusCode.Forbidden, client.putOverride(teacher, g, """{ "status": "COVERED" }""", studentId = STUDENT_2).status, "unlinked")

        val foreign = LibraryFixtures.grammar("Fremd", LanguageLevel.B1, teacherId = LibraryFixtures.otherTeacher())
        val notFound = client.putOverride(teacher, foreign, """{ "status": "COVERED" }""")
        assertEquals(HttpStatusCode.NotFound, notFound.status)
        assertEquals("GRAMMAR_TOPIC_NOT_FOUND", notFound.body<JsonObject>().str("code"))
    }

    @Test
    fun `includeLower adds lower levels and grammar without level is never listed`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        LibraryFixtures.grammar("Perfekt", LanguageLevel.A2)
        LibraryFixtures.grammar("Konjunktiv II", LanguageLevel.B1)
        LibraryFixtures.grammar("Ohne Niveau", null)
        LibraryFixtures.grammar("Partizip I", LanguageLevel.C1)
        val teacher = getTeacherToken(client)

        val only = client.progress(teacher)
        assertEquals("B1", only.str("level"))
        assertEquals(listOf("B1"), only["levels"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Konjunktiv II"), only.items().map { it.str("name") })

        val lower = client.progress(teacher, query = "?includeLower=true")
        assertEquals(listOf("A1", "A2", "B1"), lower["levels"]!!.jsonArray.map { it.jsonPrimitive.content })
        val groups = lower["grammar"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("A1", "A2", "B1"), groups.map { it.str("level") })
        assertTrue(groups[0]["checklistEmpty"]!!.jsonPrimitive.boolean, "A1 has no topics: empty, not 0 %")
        assertEquals(JsonNull, groups[0].obj("counts")["percent"])
        assertFalse(groups[1]["checklistEmpty"]!!.jsonPrimitive.boolean)
        assertEquals(100, groups[1].obj("counts").obj("percent")["notCovered"]!!.jsonPrimitive.int)
        assertEquals(listOf("Perfekt", "Konjunktiv II"), lower.items().map { it.str("name") })
        assertEquals(2, lower.obj("summary").obj("grammar")["total"]!!.jsonPrimitive.int)

        // ?level= overrides the student's level.
        assertEquals(listOf("Partizip I"), client.progress(teacher, query = "?level=C1").items().map { it.str("name") })
        assertEquals(HttpStatusCode.BadRequest, client.progressRaw(teacher, query = "?level=X9").status)
    }

    @Test
    fun `topics relevant to the level with coverage via lesson or vocab and word counts`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val alltag = LibraryFixtures.topic("Alltag")
        val haushalt = LibraryFixtures.topic("Haushalt", parentId = alltag, levels = listOf(LanguageLevel.B1))
        val reisen = LibraryFixtures.topic("Reisen", levels = listOf(LanguageLevel.A2, LanguageLevel.B1))
        LibraryFixtures.topic("Beruf", levels = listOf(LanguageLevel.C1))
        LibraryFixtures.grammar("Passiv", LanguageLevel.B1)

        val lesson = seedLesson(scheduledAt = OffsetDateTime.now().minusDays(1))
        LibraryFixtures.tagLesson(lesson, topics = listOf(haushalt))
        val koffer = LibraryFixtures.entry("Koffer", topics = listOf(reisen))
        val zug = LibraryFixtures.entry("Zug", topics = listOf(reisen))
        LibraryFixtures.assign(STUDENT, koffer)
        LibraryFixtures.assign(STUDENT, zug)
        LibraryFixtures.assign(STUDENT_2, zug)
        transaction {
            StudentVocabTable.update({ StudentVocabTable.vocabEntryId eq koffer }) { it[status] = StudentVocabStatus.LEARNED }
        }

        val body = client.progress(getTeacherToken(client))
        val topics = body["topics"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Alltag", "Haushalt", "Reisen"), topics.map { it.str("name") }, "tree order")
        val byName = topics.associateBy { it.str("name") }
        assertFalse(byName.getValue("Alltag")["covered"]!!.jsonPrimitive.boolean, "direct tags only")
        val h = byName.getValue("Haushalt")
        assertTrue(h["covered"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("LESSON"), h["via"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Alltag"), h["path"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(alltag.toString(), h.str("parentId"))
        assertTrue(h["lastLessonAt"] !is JsonNull)
        val r = byName.getValue("Reisen")
        assertEquals(listOf("VOCAB"), r["via"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, r.obj("words")["learned"]!!.jsonPrimitive.int)
        assertEquals(2, r.obj("words")["total"]!!.jsonPrimitive.int)

        val summary = body.obj("summary")
        assertEquals(3, summary.obj("topics")["total"]!!.jsonPrimitive.int)
        assertEquals(2, summary.obj("topics")["covered"]!!.jsonPrimitive.int)
        assertEquals(67, summary.obj("topics")["percent"]!!.jsonPrimitive.int)
        assertEquals(2, summary.obj("vocab")["total"]!!.jsonPrimitive.int)
        assertEquals(1, summary.obj("vocab")["learned"]!!.jsonPrimitive.int)
        assertEquals(50, summary.obj("vocab")["percent"]!!.jsonPrimitive.int)
        assertTrue("current" in summary.obj("streak"))
    }

    @Test
    fun `empty checklist and missing level give checklistEmpty instead of fake zeros`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val teacher = getTeacherToken(client)

        val noLevel = client.progress(teacher)
        assertEquals(JsonNull, noLevel["level"])
        assertTrue(noLevel["checklistEmpty"]!!.jsonPrimitive.boolean)
        assertTrue(noLevel["grammar"]!!.jsonArray.isEmpty())
        assertTrue(noLevel["topics"]!!.jsonArray.isEmpty())

        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        LibraryFixtures.grammar("Andere Stufe", LanguageLevel.A2)
        val empty = client.progress(teacher)
        assertTrue(empty["checklistEmpty"]!!.jsonPrimitive.boolean)
        assertEquals(0, empty.obj("summary").obj("grammar")["total"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, empty.obj("summary").obj("grammar")["percent"])
        assertEquals(JsonNull, empty.obj("summary").obj("topics")["percent"])
    }

    @Test
    fun `access - student reads own, others are refused`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        LibraryFixtures.grammar("Passiv", LanguageLevel.B1)

        val own = client.progress(getStudentToken(client))
        assertEquals(TEACHER.toString(), own.str("teacherId"), "the earliest teacher")
        assertEquals(1, own.items().size)
        assertEquals(HttpStatusCode.Forbidden, client.progressRaw(getStudent2Token(client)).status)
        assertEquals(HttpStatusCode.Forbidden, client.progressRaw(getTeacherToken(client), studentId = STUDENT_2).status)
        assertEquals(HttpStatusCode.OK, client.progressRaw(getAdminToken(client), query = "?teacherId=$TEACHER").status)
        val noTeacher = client.progressRaw(getStudent2Token(client), studentId = STUDENT_2)
        assertEquals(HttpStatusCode.NotFound, noTeacher.status)
        assertEquals("TEACHER_STUDENT_NOT_FOUND", noTeacher.body<JsonObject>().str("code"))
    }

    @Test
    fun `grammar merge moves overrides and delete removes them`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val teacher = getTeacherToken(client)
        val source = LibraryFixtures.grammar("Konjunktiv 2", LanguageLevel.B1)
        val target = LibraryFixtures.grammar("Konjunktiv II", LanguageLevel.B1)
        val other = LibraryFixtures.grammar("Passiv", LanguageLevel.B1)
        client.putOverride(teacher, source, """{ "status": "PRACTICED", "note": "von A" }""")

        val merge = client.post("/api/v1/grammar-topics/$source/merge") {
            bearerAuth(teacher); contentType(ContentType.Application.Json); setBody("""{ "targetId": "$target" }""")
        }
        assertEquals(HttpStatusCode.OK, merge.status)
        val merged = client.progress(teacher).item("Konjunktiv II")
        assertEquals("PRACTICED", merged.str("effective"))
        assertEquals("von A", merged.obj("override").str("note"))

        client.putOverride(teacher, other, """{ "status": "COVERED" }""")
        val deleted = client.delete("/api/v1/grammar-topics/$other?force=true") { bearerAuth(teacher) }
        assertTrue(deleted.status.isSuccess())
        assertEquals(0L, transaction {
            GrammarProgressOverrideTable.selectAll().where { GrammarProgressOverrideTable.grammarTopicId eq other }.count()
        })
    }

    @Test
    fun `the calculator runs a fixed number of queries regardless of size`() = testApp {
        startApplication()
        val topicsSmall = (1..2).map { LibraryFixtures.grammar("G$it", LanguageLevel.B1) }
        (3..30).forEach { LibraryFixtures.grammar("G$it", LanguageLevel.B1) }
        val many = (1..6).map { ProgressFixtures.linkedStudent("s$it@test.com") }
        many.forEach { student ->
            ProgressFixtures.homework(student, documentId = LibraryFixtures.document("D$student", grammar = topicsSmall), units = listOf(WRONG, RIGHT))
            LibraryFixtures.tagLesson(seedLesson(students = listOf(student)), grammar = topicsSmall)
        }
        (1..10).forEach { LibraryFixtures.topic("T$it", levels = listOf(LanguageLevel.B1)) }
        val calculator = ProgressCalculator(ProgressConfig(0.6, 3))

        fun count(students: List<UUID>, allTopics: Boolean): Int = transaction {
            var statements = 0
            registerInterceptor(object : StatementInterceptor {
                override fun beforeExecution(transaction: Transaction, context: StatementContext) { statements++ }
            })
            val topics = calculator.grammarTopics(TEACHER, listOf(LanguageLevel.B1)).let { if (allTopics) it else it.take(2) }
            calculator.grammar(TEACHER, students, topics)
            calculator.topics(TEACHER, students) { true }
            statements
        }
        val small = count(listOf(STUDENT), allTopics = false)
        assertTrue(small >= 5, "statements are counted ($small)")
        assertEquals(small, count(many, allTopics = true))
    }
}

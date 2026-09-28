package com.gvart.parleyroom.goal

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.progress.ProgressFixtures
import com.gvart.parleyroom.progress.RIGHT
import com.gvart.parleyroom.progress.WRONG
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalIntegrationTest : IntegrationTest() {

    private val today: LocalDate get() = LocalDate.now(ZoneId.of("Europe/Berlin"))

    private suspend fun HttpClient.createGoal(token: String, body: String): HttpResponse = post("/api/v1/goals") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpClient.created(token: String, body: String): JsonObject {
        val response = createGoal(token, body)
        assertEquals(HttpStatusCode.Created, response.status, body)
        return response.body()
    }

    private suspend fun HttpClient.patchGoal(token: String, id: String, body: String): HttpResponse = patch("/api/v1/goals/$id") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.int

    /** B1: PRACTICED, COVERED, NEEDS_WORK, NOT_COVERED; two B1 topics, one covered; one A2 topic, uncovered. */
    private fun seedB1() {
        val practiced = LibraryFixtures.grammar("Geübt", LanguageLevel.B1)
        val covered = LibraryFixtures.grammar("Behandelt", LanguageLevel.B1)
        val weak = LibraryFixtures.grammar("Schwach", LanguageLevel.B1)
        LibraryFixtures.grammar("Offen", LanguageLevel.B1)
        LibraryFixtures.grammar("A2-Thema", LanguageLevel.A2)
        ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("D1", grammar = listOf(practiced)), units = listOf(RIGHT))
        ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("D2", grammar = listOf(weak)), units = listOf(WRONG, WRONG, WRONG))
        val haushalt = LibraryFixtures.topic("Haushalt", levels = listOf(LanguageLevel.B1))
        LibraryFixtures.topic("Reisen", levels = listOf(LanguageLevel.B1))
        LibraryFixtures.topic("Ohne Niveau")
        LibraryFixtures.tagLesson(seedLesson(), topics = listOf(haushalt), grammar = listOf(covered))
    }

    @Test
    fun `exam goal progress follows the documented formula and stores the baseline`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        seedB1()
        val target = today.plusDays(60)
        val goal = client.created(
            getTeacherToken(client),
            """{ "studentId": "$STUDENT_ID", "type": "EXAM", "examName": " telc B1 ", "targetLevel": "B1", "targetDate": "$target", "note": "Prüfung im Herbst" }""",
        )
        assertEquals("EXAM", goal.str("type"))
        assertEquals("telc B1", goal.str("examName"))
        assertEquals("ACTIVE", goal.str("status"))
        assertEquals(TEACHER_ID, goal.str("teacherId"))
        val progress = goal.obj("progress")
        // grammar (1 + 0.5 + 0.5 + 0) / 4 = 0.5; topics 1/2 = 0.5 -> 0.8·0.5 + 0.2·0.5 = 50 %
        assertEquals(50, progress.int("percent"))
        assertEquals(50, goal.int("baselinePercent"))
        assertFalse(progress["checklistEmpty"]!!.jsonPrimitive.boolean)
        val grammar = progress.obj("grammar")
        assertEquals(listOf(4, 1, 1, 1, 1), listOf("total", "practiced", "covered", "needsWork", "notCovered").map { grammar.int(it) })
        assertEquals(2, progress.obj("topics").int("total"), "unleveled topics do not count for a goal")
        assertEquals(1, progress.obj("topics").int("covered"))
        assertEquals(60, progress.int("daysLeft"))
        assertEquals(50, progress.int("expectedPercent"), "day 0: expected = baseline")
        assertTrue(progress["onTrack"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `without level topics the grammar score alone counts and an empty checklist gives null`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val teacher = getTeacherToken(client)
        val g = LibraryFixtures.grammar("Passiv", LanguageLevel.C1)
        LibraryFixtures.grammar("Partizip", LanguageLevel.C1)
        LibraryFixtures.tagLesson(seedLesson(), grammar = listOf(g))

        val c1 = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "C1" }""")
        assertEquals(25, c1.obj("progress").int("percent"), "(0.5 + 0) / 2")
        assertEquals(JsonNull, c1.obj("progress")["daysLeft"])
        assertEquals(JsonNull, c1.obj("progress")["onTrack"])
        assertEquals(JsonNull, c1.obj("progress")["expectedPercent"])

        val empty = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "C2", "targetDate": "${today.plusDays(10)}" }""")
        assertEquals(JsonNull, empty.obj("progress")["percent"])
        assertTrue(empty.obj("progress")["checklistEmpty"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, empty["baselinePercent"])
        assertEquals(10, empty.obj("progress").int("daysLeft"))
        assertEquals(JsonNull, empty.obj("progress")["onTrack"])
    }

    @Test
    fun `on track compares with the baseline line over elapsed time`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        seedB1()
        val teacher = getTeacherToken(client)
        val goal = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "B1", "targetDate": "${today.plusDays(10)}" }""")
        val id = UUID.fromString(goal.str("id"))
        fun rewind(baseline: Int?) = transaction {
            GoalTable.update({ GoalTable.id eq id }) {
                it[createdAt] = OffsetDateTime.now().minusDays(10)
                it[baselinePercent] = baseline
            }
        }
        suspend fun progress() = client.get("/api/v1/goals/$id") { bearerAuth(teacher) }.body<JsonObject>().obj("progress")

        rewind(baseline = 0)     // half the time gone, expected 50, actual 50
        assertEquals(50, progress().int("expectedPercent"))
        assertTrue(progress()["onTrack"]!!.jsonPrimitive.boolean)

        rewind(baseline = 60)    // expected 60 + 0.5·40 = 80; 50 < 70
        assertEquals(80, progress().int("expectedPercent"))
        assertFalse(progress()["onTrack"]!!.jsonPrimitive.boolean)

        rewind(baseline = null)  // null baseline counts as 0
        assertEquals(50, progress().int("expectedPercent"))

        val archived = client.patchGoal(teacher, id.toString(), """{ "status": "ARCHIVED" }""").body<JsonObject>()
        assertEquals("ARCHIVED", archived.str("status"))
        assertNotNull(archived["statusChangedAt"]?.takeIf { it !is JsonNull })
        assertEquals(JsonNull, archived.obj("progress")["onTrack"])
        assertEquals(50, archived.obj("progress").int("percent"), "still computed")
    }

    @Test
    fun `validation of goal input`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val teacher = getTeacherToken(client)
        suspend fun expect400(body: String, pointer: String) {
            val response = client.createGoal(teacher, body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            val problem = response.body<JsonObject>()
            assertEquals("GOAL_INVALID", problem.str("code"), body)
            assertEquals(pointer, problem.str("pointer"), body)
        }
        val s = "\"studentId\": \"$STUDENT_ID\""
        expect400("""{ $s, "type": "EXAM", "targetLevel": "B1", "targetDate": "${today.plusDays(5)}" }""", "/examName")
        expect400("""{ $s, "type": "EXAM", "examName": "telc B1", "targetLevel": "B1" }""", "/targetDate")
        expect400("""{ $s, "type": "LEVEL", "examName": "telc", "targetLevel": "B1" }""", "/examName")
        expect400("""{ $s, "type": "GOETHE", "targetLevel": "B1" }""", "/type")
        expect400("""{ $s, "type": "LEVEL", "targetLevel": "B3" }""", "/targetLevel")
        expect400("""{ $s, "type": "LEVEL" }""", "/targetLevel")
        expect400("""{ $s, "type": "LEVEL", "targetLevel": "B1", "targetDate": "01.12.2026" }""", "/targetDate")
        expect400("""{ $s, "type": "LEVEL", "targetLevel": "B1", "targetDate": "${today.minusDays(1)}" }""", "/targetDate")
        expect400("""{ $s, "type": "EXAM", "examName": "${"x".repeat(101)}", "targetLevel": "B1", "targetDate": "${today}" }""", "/examName")
        expect400("""{ $s, "type": "LEVEL", "targetLevel": "B1", "note": "${"x".repeat(2001)}" }""", "/note")
        expect400("""{ "studentId": "nope", "type": "LEVEL", "targetLevel": "B1" }""", "/studentId")

        val body = """{ $s, "type": "LEVEL", "targetLevel": "B1" }"""
        assertEquals(HttpStatusCode.Forbidden, client.createGoal(getStudentToken(client), body).status)
        assertEquals(HttpStatusCode.Forbidden, client.createGoal(getAdminToken(client), body).status)
        assertEquals(HttpStatusCode.Forbidden, client.createGoal(teacher, """{ "studentId": "$STUDENT_2_ID", "type": "LEVEL", "targetLevel": "B1" }""").status)
        // Old endpoints are gone.
        assertEquals(HttpStatusCode.NotFound, client.put("/api/v1/goals/${UUID.randomUUID()}/progress") { bearerAuth(teacher) }.status)
    }

    @Test
    fun `list visibility, filters and ordering`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val teacher = getTeacherToken(client)
        val later = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "B2", "targetDate": "${today.plusDays(90)}" }""")
        val sooner = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "EXAM", "examName": "telc B1", "targetLevel": "B1", "targetDate": "${today.plusDays(30)}" }""")
        val noDate = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "C1" }""")
        val done = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "A2" }""")
        client.patchGoal(teacher, done.str("id"), """{ "status": "ACHIEVED" }""")

        suspend fun ids(token: String, query: String = "") =
            client.get("/api/v1/goals$query") { bearerAuth(token) }.body<JsonArray>().map { it.jsonObject.str("id") }
        assertEquals(listOf(sooner, later, noDate, done).map { it.str("id") }, ids(teacher))
        assertEquals(listOf(sooner, later, noDate, done).map { it.str("id") }, ids(getStudentToken(client)), "the student reads own goals")
        assertEquals(listOf(done.str("id")), ids(teacher, "?status=ACHIEVED"))
        assertEquals(3, ids(teacher, "?status=ACTIVE,ARCHIVED").size)
        assertEquals(emptyList(), ids(getStudent2Token(client)))
        assertEquals(4, ids(getAdminToken(client)).size)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/goals?studentId=$STUDENT_2_ID") { bearerAuth(teacher) }.status)

        // Another teacher neither sees nor edits them.
        LibraryFixtures.otherTeacher()
        val other = getToken(client, "teacher2@test.com")
        assertEquals(emptyList(), ids(other))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/goals/${sooner.str("id")}") { bearerAuth(other) }.status)
        val patched = client.patchGoal(other, sooner.str("id"), """{ "note": "x" }""")
        assertEquals(HttpStatusCode.NotFound, patched.status)
        assertEquals("GOAL_NOT_FOUND", patched.body<JsonObject>().str("code"))
    }

    @Test
    fun `admin stats count active goals as learningGoals`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val teacher = getTeacherToken(client)
        client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "B1" }""")
        val archived = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "B2" }""")
        client.patchGoal(teacher, archived.str("id"), """{ "status": "ARCHIVED" }""")

        val stats = client.get("/api/v1/admin/stats") { bearerAuth(getAdminToken(client)) }.body<JsonObject>()
        assertEquals(1, stats.obj("domain").int("learningGoals"))
    }

    @Test
    fun `patch rules and delete`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val teacher = getTeacherToken(client)
        val exam = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "EXAM", "examName": "telc B1", "targetLevel": "B1", "targetDate": "${today.plusDays(30)}", "note": "alt" }""")
        val level = client.created(teacher, """{ "studentId": "$STUDENT_ID", "type": "LEVEL", "targetLevel": "B1", "targetDate": "${today.plusDays(30)}" }""")

        val updated = client.patchGoal(teacher, exam.str("id"), """{ "examName": "Goethe B2", "targetLevel": "B2", "targetDate": "${today.plusDays(40)}", "clearNote": true }""")
        assertEquals(HttpStatusCode.OK, updated.status)
        val body = updated.body<JsonObject>()
        assertEquals("Goethe B2", body.str("examName"))
        assertEquals("B2", body.str("targetLevel"))
        assertEquals(JsonNull, body["note"])
        assertEquals(JsonNull, body["statusChangedAt"], "status untouched")

        suspend fun expect400(id: String, patch: String, pointer: String) {
            val response = client.patchGoal(teacher, id, patch)
            assertEquals(HttpStatusCode.BadRequest, response.status, patch)
            assertEquals(pointer, response.body<JsonObject>().str("pointer"))
        }
        expect400(exam.str("id"), """{ "clearTargetDate": true }""", "/clearTargetDate")
        expect400(exam.str("id"), """{ "examName": "  " }""", "/examName")
        expect400(level.str("id"), """{ "examName": "telc" }""", "/examName")
        expect400(level.str("id"), """{ "status": "DONE" }""", "/status")

        val cleared = client.patchGoal(teacher, level.str("id"), """{ "clearTargetDate": true }""").body<JsonObject>()
        assertEquals(JsonNull, cleared["targetDate"])

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/goals/${level.str("id")}") { bearerAuth(teacher) }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/goals/${exam.str("id")}") { bearerAuth(getAdminToken(client)) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/goals/${exam.str("id")}") { bearerAuth(teacher) }.status)
    }
}

package com.gvart.parleyroom.progress

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.ai.transfer.DraftContextResponse
import com.gvart.parleyroom.ai.transfer.GrammarGaps
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.progress.data.StudentGrammarCoverageTable
import com.gvart.parleyroom.progress.service.GrammarCoverageService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.di.dependencies
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrammarCoverageIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.progress(token: String, studentId: UUID = STUDENT): JsonObject {
        val response = get("/api/v1/students/$studentId/progress") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private fun JsonObject.item(name: String) = this["grammar"]!!.jsonArray
        .flatMap { it.jsonObject["items"]!!.jsonArray }.map { it.jsonObject }
        .single { it["name"]!!.jsonPrimitive.content == name }
    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    @Test
    fun `a coverage row is a COVERED floor without an override, visible to the student`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val covered = LibraryFixtures.grammar("Perfekt", LanguageLevel.B1, position = 0)
        LibraryFixtures.grammar("Passiv", LanguageLevel.B1, position = 1)
        val coverage = application.dependencies.resolve<GrammarCoverageService>()
        transaction { coverage.markCovered(TEACHER, listOf(STUDENT), listOf(covered), bundleId = null) }
        // First coverage wins; a repeat is a no-op.
        transaction { coverage.markCovered(TEACHER, listOf(STUDENT), listOf(covered, covered), bundleId = null) }
        assertEquals(1L, transaction { StudentGrammarCoverageTable.selectAll().count() })

        val item = client.progress(getTeacherToken(client)).item("Perfekt")
        assertEquals("COVERED", item.str("derived"))
        assertEquals("COVERED", item.str("effective"))
        assertEquals(JsonNull, item["override"])
        assertEquals(0, item.obj("evidence")["lessonCount"]!!.jsonPrimitive.int)
        assertTrue(item.obj("evidence")["coveredAt"] !is JsonNull)

        val own = client.progress(getStudentToken(client))
        assertEquals("COVERED", own.item("Perfekt").str("effective"))
        assertEquals("NOT_COVERED", own.item("Passiv").str("effective"))
        assertEquals(1, own.obj("summary").obj("grammar")["covered"]!!.jsonPrimitive.int)
    }

    @Test
    fun `stronger evidence still wins over the floor and an override beats it`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val weak = LibraryFixtures.grammar("Konjunktiv II", LanguageLevel.B1, position = 0)
        val practiced = LibraryFixtures.grammar("Relativsätze", LanguageLevel.B1, position = 1)
        val overridden = LibraryFixtures.grammar("Genitiv", LanguageLevel.B1, position = 2)
        val coverage = application.dependencies.resolve<GrammarCoverageService>()
        transaction { coverage.markCovered(TEACHER, listOf(STUDENT), listOf(weak, practiced, overridden), bundleId = null) }
        ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("Schwach", grammar = listOf(weak)), units = listOf(WRONG, WRONG, RIGHT))
        ProgressFixtures.homework(STUDENT, documentId = LibraryFixtures.document("Geübt", grammar = listOf(practiced)), status = HomeworkStatus.SUBMITTED)
        val teacher = getTeacherToken(client)
        val put = client.put("/api/v1/students/$STUDENT/progress/grammar/$overridden/override") {
            bearerAuth(teacher); contentType(ContentType.Application.Json); setBody("""{ "status": "NOT_COVERED" }""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val body = client.progress(teacher)
        assertEquals("NEEDS_WORK", body.item("Konjunktiv II").str("effective"), "the floor does not freeze the status")
        assertEquals("PRACTICED", body.item("Relativsätze").str("effective"))
        assertEquals("COVERED", body.item("Genitiv").str("derived"))
        assertEquals("NOT_COVERED", body.item("Genitiv").str("effective"), "a manual override beats the floor")
    }

    @Test
    fun `the AI context no longer lists covered grammar as a gap`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val token = getTeacherToken(client)
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val covered = LibraryFixtures.grammar("Perfekt", LanguageLevel.B1, position = 0)
        LibraryFixtures.grammar("Passiv", LanguageLevel.B1, position = 1)
        val coverage = application.dependencies.resolve<GrammarCoverageService>()
        transaction { coverage.markCovered(TEACHER, listOf(STUDENT), listOf(covered), bundleId = null) }
        val lessonId = seedLesson()

        val state = client.get("/api/v1/lessons/$lessonId/draft-context") { bearerAuth(token) }.body<DraftContextResponse>()
        assertEquals(GrammarGaps(emptyList(), listOf("Passiv"), 0, 1), state.context.grammarGaps)
    }

    @Test
    fun `grammar merge moves coverage rows and keeps the target's on a clash`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        ProgressFixtures.setLevel(STUDENT, LanguageLevel.B1)
        val student3 = ProgressFixtures.linkedStudent("s3@test.com")
        val source = LibraryFixtures.grammar("Konjunktiv 2", LanguageLevel.B1)
        val target = LibraryFixtures.grammar("Konjunktiv II", LanguageLevel.B1)
        val coverage = application.dependencies.resolve<GrammarCoverageService>()
        transaction {
            coverage.markCovered(TEACHER, listOf(STUDENT, student3), listOf(source), bundleId = null)
            coverage.markCovered(TEACHER, listOf(student3), listOf(target), bundleId = null)
        }
        val teacher = getTeacherToken(client)

        val merge = client.post("/api/v1/grammar-topics/$source/merge") {
            bearerAuth(teacher); contentType(ContentType.Application.Json); setBody("""{ "targetId": "$target" }""")
        }
        assertEquals(HttpStatusCode.OK, merge.status)
        val rows = transaction {
            StudentGrammarCoverageTable.selectAll().map { it[StudentGrammarCoverageTable.studentId].value to it[StudentGrammarCoverageTable.grammarTopicId].value }
        }
        assertEquals(setOf(STUDENT to target, student3 to target), rows.toSet())
        assertEquals(2, rows.size)
        assertEquals("COVERED", client.progress(teacher).item("Konjunktiv II").str("effective"))
    }
}

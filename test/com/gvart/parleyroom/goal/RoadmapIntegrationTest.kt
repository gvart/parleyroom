package com.gvart.parleyroom.goal

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.STUDENT_2
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.ai.seedLesson
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.RoadmapItemState.NOT_STARTED
import com.gvart.parleyroom.goal.data.SelfAssessmentAnswer
import com.gvart.parleyroom.goal.transfer.MyGoalResponse
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadmapIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.send(method: String, token: String, path: String, json: String? = null): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        return when (method) {
            "POST" -> post(path, block)
            "PUT" -> put(path, block)
            "PATCH" -> patch(path, block)
            "DELETE" -> delete(path, block)
            else -> get(path, block)
        }
    }

    private suspend fun HttpResponse.view(status: HttpStatusCode = HttpStatusCode.OK): TeacherGoalView {
        assertEquals(status, this.status, bodyAsText())
        return body()
    }

    private suspend fun HttpResponse.problem(status: HttpStatusCode, code: String): ProblemDetail {
        assertEquals(status, this.status, bodyAsText())
        return body<ProblemDetail>().also { assertEquals(code, it.code) }
    }

    private fun milestoneJson(title: String, position: Int? = null) = """
        { "title": "$title", "startDate": "2026-11-01", "endDate": "2026-11-14", "canDo": "Ich kann $title."
          ${position?.let { """, "position": $it""" }.orEmpty()} }
    """

    private fun notifications(userId: UUID, type: NotificationType) = transaction {
        NotificationTable.selectAll().where { (NotificationTable.userId eq userId) and (NotificationTable.type eq type) }.count()
    }

    @Test
    fun `milestones are added, edited, reordered and deleted with contiguous positions`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = GoalFixtures.goal()
        val base = "/api/v1/goals/$goal/milestones"

        client.send("POST", teacher, base, milestoneJson("A")).view(HttpStatusCode.Created)
        client.send("POST", teacher, base, milestoneJson("B")).view(HttpStatusCode.Created)
        var view = client.send("POST", teacher, base, milestoneJson("Z", position = 1)).view(HttpStatusCode.Created)
        assertEquals(listOf("Z" to 1, "A" to 2, "B" to 3), view.milestones.map { it.title to it.position })
        val (z, a, b) = view.milestones.map { it.id }

        view = client.send("PATCH", teacher, "$base/$a", """{ "title": "A2", "weight": 2.5, "grammarPoints": ["Perfekt"], "topic": "" }""").view()
        val edited = view.milestones.single { it.id == a }
        assertEquals("A2", edited.title)
        assertEquals(2.5, edited.weight)
        assertEquals(listOf("Perfekt"), edited.grammarPoints)

        view = client.send("PUT", teacher, "$base/order", """{ "ids": ["$b", "$a", "$z"] }""").view()
        assertEquals(listOf(b to 1, a to 2, z to 3), view.milestones.map { it.id to it.position })

        val missing = client.send("PUT", teacher, "$base/order", """{ "ids": ["$b", "$a"] }""").problem(HttpStatusCode.BadRequest, "ROADMAP_ORDER_INVALID")
        assertEquals("/ids", missing.errors?.single()?.pointer)

        view = client.send("DELETE", teacher, "$base/$b").view()
        assertEquals(listOf(a to 1, z to 2), view.milestones.map { it.id to it.position })

        val invalid = client.send("POST", teacher, base, """{ "title": "", "startDate": "2026-11-10", "endDate": "2026-11-01", "weight": 0 }""")
            .problem(HttpStatusCode.BadRequest, "VALIDATION_FAILED")
        assertEquals(listOf("/title", "/endDate", "/weight", "/canDo"), invalid.errors!!.map { it.pointer })

        client.send("PATCH", teacher, "$base/${UUID.randomUUID()}", """{ "title": "x" }""").problem(HttpStatusCode.NotFound, "ROADMAP_MILESTONE_NOT_FOUND")
    }

    @Test
    fun `items are added, reordered, moved between milestones and checked in a lesson`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = GoalFixtures.goal()
        val first = GoalFixtures.milestone(goal, 1)
        val second = GoalFixtures.milestone(goal, 2, listOf(NOT_STARTED))
        val base = "/api/v1/goals/$goal"

        client.send("POST", teacher, "$base/milestones/$first/items", """{ "kind": "GRAMMAR", "title": "Perfekt mit haben" }""").view(HttpStatusCode.Created)
        client.send("POST", teacher, "$base/milestones/$first/items", """{ "kind": "VOCAB", "title": "Familie" }""").view(HttpStatusCode.Created)
        var view = client.send("POST", teacher, "$base/milestones/$first/items", """{ "kind": "SKILL", "title": "Vorstellen" }""")
            .view(HttpStatusCode.Created)
        val (perfekt, familie, vorstellen) = view.milestones[0].items.map { it.id }

        view = client.send("PUT", teacher, "$base/milestones/$first/items/order", """{ "ids": ["$vorstellen", "$perfekt", "$familie"] }""").view()
        assertEquals(listOf("Vorstellen", "Perfekt mit haben", "Familie"), view.milestones[0].items.map { it.title })

        view = client.send("POST", teacher, "$base/items/$perfekt/move", """{ "milestoneId": "$second", "position": 1 }""").view()
        assertEquals(listOf(vorstellen to 1, familie to 2), view.milestones[0].items.map { it.id to it.position })
        assertEquals(listOf(perfekt, view.milestones[1].items[1].id), view.milestones[1].items.map { it.id })
        assertEquals(listOf(1, 2), view.milestones[1].items.map { it.position })

        val lesson = seedLesson()
        view = client.send("PATCH", teacher, "$base/items/$familie", """{ "state": "DONE", "lessonId": "$lesson", "title": "Familie und Freunde" }""").view()
        val item = view.milestones[0].items.single { it.id == familie }
        assertEquals(RoadmapItemState.DONE, item.state)
        assertEquals(lesson.toString(), item.lessonId)
        assertEquals("Familie und Freunde", item.title)
        assertNotNull(item.stateChangedAt)

        val otherLesson = seedLesson(students = listOf(STUDENT_2))
        client.send("PATCH", teacher, "$base/items/$familie", """{ "lessonId": "$otherLesson" }""")
            .problem(HttpStatusCode.BadRequest, "ROADMAP_LESSON_INVALID").also { assertEquals("/lessonId", it.pointer) }

        view = client.send("DELETE", teacher, "$base/items/$vorstellen").view()
        assertEquals(listOf(familie to 1), view.milestones[0].items.map { it.id to it.position })
    }

    @Test
    fun `batch item states after a lesson are idempotent and all or nothing`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = GoalFixtures.goal()
        GoalFixtures.milestone(goal, 1, listOf(NOT_STARTED, NOT_STARTED, NOT_STARTED))
        val items = client.send("GET", teacher, "/api/v1/goals/$goal").view().milestones[0].items.map { it.id }
        val lesson = seedLesson()
        val body = """{ "lessonId": "$lesson", "items": [ { "itemId": "${items[0]}", "state": "DONE" }, { "itemId": "${items[1]}", "state": "IN_PROGRESS" } ] }"""

        val once = client.send("PATCH", teacher, "/api/v1/goals/$goal/items", body).view()
        val twice = client.send("PATCH", teacher, "/api/v1/goals/$goal/items", body).view()
        assertEquals(once.milestones, twice.milestones)
        assertEquals(once.progress, twice.progress)
        assertEquals(listOf(RoadmapItemState.DONE, RoadmapItemState.IN_PROGRESS, NOT_STARTED), twice.milestones[0].items.map { it.state })
        assertEquals(listOf(lesson.toString(), lesson.toString(), null), twice.milestones[0].items.map { it.lessonId })
        assertEquals(33, twice.progress.percent)

        val unknown = """{ "items": [ { "itemId": "${items[2]}", "state": "DONE" }, { "itemId": "${UUID.randomUUID()}", "state": "DONE" } ] }"""
        client.send("PATCH", teacher, "/api/v1/goals/$goal/items", unknown).problem(HttpStatusCode.NotFound, "ROADMAP_ITEM_NOT_FOUND")
        assertEquals(NOT_STARTED, client.send("GET", teacher, "/api/v1/goals/$goal").view().milestones[0].items[2].state)

        val bad = client.send("PATCH", teacher, "/api/v1/goals/$goal/items", """{ "items": [ { "itemId": "${items[0]}", "state": "FINISHED" } ] }""")
            .problem(HttpStatusCode.BadRequest, "VALIDATION_FAILED")
        assertEquals("/items", bad.errors?.single()?.pointer)
    }

    @Test
    fun `done milestone asks the student to self-assess and a mismatch notifies the teacher`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val goal = GoalFixtures.goal()
        val milestone = GoalFixtures.milestone(goal, 1, listOf(NOT_STARTED))
        GoalFixtures.milestone(goal, 2)
        val assess = "/api/v1/goals/my/milestones/$milestone/self-assessment"

        client.send("POST", student, assess, """{ "answer": "YES" }""").problem(HttpStatusCode.Conflict, "MILESTONE_NOT_DONE")

        val done = client.send("POST", teacher, "/api/v1/goals/$goal/milestones/$milestone/done").view()
        assertNotNull(done.milestones[0].doneAt)
        client.send("POST", teacher, "/api/v1/goals/$goal/milestones/$milestone/done").view()
        assertEquals(1, notifications(STUDENT, NotificationType.MILESTONE_COMPLETED), "repeating done notifies once")

        val my = client.send("GET", student, "/api/v1/goals/my").body<MyGoalResponse>().goal!!
        assertEquals(milestone.toString(), my.pendingSelfAssessment?.milestoneId)

        // Another student cannot answer for it.
        client.send("POST", getStudent2Token(client), assess, """{ "answer": "YES" }""").problem(HttpStatusCode.NotFound, "ROADMAP_MILESTONE_NOT_FOUND")
        client.send("POST", teacher, assess, """{ "answer": "YES" }""").problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        client.send("POST", student, assess, """{ "answer": "MAYBE" }""").problem(HttpStatusCode.BadRequest, "VALIDATION_FAILED")
            .also { assertEquals("/answer", it.errors?.single()?.pointer) }

        val answered = client.send("POST", student, assess, """{ "answer": "NOT_YET", "comment": "Perfekt ist schwer" }""")
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
        assertNull(answered.body<MyGoalResponse>().goal!!.pendingSelfAssessment)
        assertEquals(1, notifications(TEACHER, NotificationType.SELF_ASSESSMENT_MISMATCH))

        var view = client.send("GET", teacher, "/api/v1/goals/$goal").view()
        val assessment = view.milestones[0].selfAssessment!!
        assertEquals(SelfAssessmentAnswer.NOT_YET, assessment.answer)
        assertEquals("Perfekt ist schwer", assessment.comment)
        assertTrue(assessment.mismatch)
        assertEquals(listOf(milestone.toString()), view.openMismatches.map { it.milestoneId })

        // The same answer again does not notify twice; YES resolves the mismatch.
        client.send("POST", student, assess, """{ "answer": "NOT_YET" }""")
        assertEquals(1, notifications(TEACHER, NotificationType.SELF_ASSESSMENT_MISMATCH))
        client.send("POST", student, assess, """{ "answer": "YES" }""")
        view = client.send("GET", teacher, "/api/v1/goals/$goal").view()
        assertFalse(view.milestones[0].selfAssessment!!.mismatch)
        assertTrue(view.openMismatches.isEmpty())

        // Undone: no longer done, the answer stays.
        view = client.send("DELETE", teacher, "/api/v1/goals/$goal/milestones/$milestone/done").view()
        assertNull(view.milestones[0].doneAt)
        assertEquals(SelfAssessmentAnswer.YES, view.milestones[0].selfAssessment?.answer)
    }
}

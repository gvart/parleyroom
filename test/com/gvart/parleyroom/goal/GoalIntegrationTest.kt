package com.gvart.parleyroom.goal

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.STUDENT_2
import com.gvart.parleyroom.common.transfer.FieldError
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.RoadmapItemState.DONE
import com.gvart.parleyroom.goal.data.RoadmapItemState.IN_PROGRESS
import com.gvart.parleyroom.goal.data.RoadmapItemState.NOT_STARTED
import com.gvart.parleyroom.goal.service.GoalTrackStatus
import com.gvart.parleyroom.goal.transfer.GoalPageResponse
import com.gvart.parleyroom.goal.transfer.MyGoalResponse
import com.gvart.parleyroom.goal.transfer.TeacherGoalView
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalIntegrationTest : IntegrationTest() {

    private fun body(studentId: UUID = STUDENT, title: String = "Goethe A1 bestehen") = """
        { "studentId": "$studentId", "title": "$title", "exam": "Goethe Start Deutsch 1", "targetLevel": "A1",
          "startDate": "2026-10-12", "deadline": "2027-03-01", "lessonsPerWeek": 2, "lessonMinutes": 60,
          "levelNote": "kennt das Alphabet", "interests": "Kochen, Fußball", "materials": "Menschen A1" }
    """

    private suspend fun HttpClient.createGoal(token: String, json: String): HttpResponse = post("/api/v1/goals") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(json)
    }

    private suspend fun HttpClient.patchGoal(token: String, id: Any, json: String): HttpResponse = patch("/api/v1/goals/$id") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(json)
    }

    private suspend fun HttpResponse.problem(status: HttpStatusCode, code: String): ProblemDetail {
        assertEquals(status, this.status, bodyAsText())
        return body<ProblemDetail>().also { assertEquals(code, it.code) }
    }

    @Test
    fun `with the roadmap flag off the goal routes are 404`() = testApp {
        startApplication()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val goal = GoalFixtures.goal()

        client.get("/api/v1/goals") { bearerAuth(teacher) }.problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.get("/api/v1/goals/$goal") { bearerAuth(teacher) }.problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.createGoal(teacher, body()).problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.post("/api/v1/goals/$goal/roadmap/generate") {
            bearerAuth(teacher); contentType(ContentType.Application.Json); setBody("{}")
        }.problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.get("/api/v1/goals/my") { bearerAuth(getStudentToken(client)) }.problem(HttpStatusCode.NotFound, "NOT_FOUND")
    }

    @Test
    fun `teacher creates, edits and archives a goal - one active goal per student`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)

        val created = client.createGoal(teacher, body())
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val goal = created.body<TeacherGoalView>()
        assertEquals("Goethe A1 bestehen", goal.title)
        assertEquals("2027-03-01", goal.deadline)
        assertEquals(2.0, goal.lessonsPerWeek)
        assertEquals("Kochen, Fußball", goal.interests)
        assertEquals(GoalStatus.ACTIVE, goal.status)
        assertNull(goal.progress.percent)
        assertEquals(GoalTrackStatus.NO_ROADMAP, goal.progress.status)
        assertTrue(goal.milestones.isEmpty())

        client.createGoal(teacher, body(title = "Zweites Ziel")).problem(HttpStatusCode.Conflict, "GOAL_ACTIVE_EXISTS")

        val patched = client.patchGoal(teacher, goal.id, """{ "title": "A1 im März", "exam": "", "lessonMinutes": 90 }""")
            .body<TeacherGoalView>()
        assertEquals("A1 im März", patched.title)
        assertNull(patched.exam)
        assertEquals(90, patched.lessonMinutes)
        assertEquals("kennt das Alphabet", patched.levelNote)

        assertEquals(GoalStatus.ARCHIVED, client.patchGoal(teacher, goal.id, """{ "status": "ARCHIVED" }""").body<TeacherGoalView>().status)
        assertEquals(HttpStatusCode.Created, client.createGoal(teacher, body(title = "Neues Ziel")).status)
        client.patchGoal(teacher, goal.id, """{ "status": "ACTIVE" }""").problem(HttpStatusCode.Conflict, "GOAL_ACTIVE_EXISTS")

        val page = client.get("/api/v1/goals?studentId=$STUDENT&pageSize=1") { bearerAuth(teacher) }.body<GoalPageResponse>()
        assertEquals(2, page.total)
        assertEquals("Neues Ziel", page.goals.single().title, "ACTIVE first")
    }

    @Test
    fun `invalid input is a problem with field errors`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val problem = client.createGoal(getTeacherToken(client), """
            { "studentId": "$STUDENT", "title": " ", "targetLevel": "B3", "startDate": "2026-10-12", "deadline": "2026-10-01",
              "lessonsPerWeek": 0, "lessonMinutes": 60 }
        """).problem(HttpStatusCode.BadRequest, "VALIDATION_FAILED")
        assertEquals(
            listOf("/title", "/targetLevel", "/deadline", "/lessonsPerWeek"),
            problem.errors!!.map { it.pointer },
        )
        assertTrue(problem.errors.all { it.code == "VALIDATION_FAILED" })

        val missing = client.createGoal(getTeacherToken(client), "{}").problem(HttpStatusCode.BadRequest, "VALIDATION_FAILED")
        assertEquals(
            listOf("/studentId", "/title", "/startDate", "/deadline", "/lessonsPerWeek", "/lessonMinutes"),
            missing.errors!!.map(FieldError::pointer),
        )
    }

    @Test
    fun `permission matrix`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        GoalFixtures.secondTeacher()
        val client = createJsonClient(this)
        val goal = GoalFixtures.goal()
        val other = getToken(client, "teacher2@test.com")
        val student = getStudentToken(client)

        // Another teacher cannot see or touch the goal: 404, not 403, so ids cannot be probed.
        client.get("/api/v1/goals/$goal") { bearerAuth(other) }.problem(HttpStatusCode.NotFound, "GOAL_NOT_FOUND")
        client.patchGoal(other, goal, """{ "title": "x" }""").problem(HttpStatusCode.NotFound, "GOAL_NOT_FOUND")
        client.delete("/api/v1/goals/$goal") { bearerAuth(other) }.problem(HttpStatusCode.NotFound, "GOAL_NOT_FOUND")
        assertEquals(0, client.get("/api/v1/goals") { bearerAuth(other) }.body<GoalPageResponse>().total)
        // ... nor set one for a student who is not theirs.
        client.createGoal(other, body()).problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        client.createGoal(getTeacherToken(client), body(STUDENT_2)).problem(HttpStatusCode.Forbidden, "FORBIDDEN")

        // The student never gets the teacher view or the list.
        client.get("/api/v1/goals/$goal") { bearerAuth(student) }.problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        client.get("/api/v1/goals") { bearerAuth(student) }.problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        client.createGoal(student, body()).problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        // Teachers have no "my goal".
        client.get("/api/v1/goals/my") { bearerAuth(getTeacherToken(client)) }.problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        // Student 2 has no goal.
        assertNull(client.get("/api/v1/goals/my") { bearerAuth(getStudent2Token(client)) }.body<MyGoalResponse>().goal)

        // Admins read and edit any goal.
        val admin = getAdminToken(client)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/goals/$goal") { bearerAuth(admin) }.status)
        assertEquals("Admin", client.patchGoal(admin, goal, """{ "title": "Admin" }""").body<TeacherGoalView>().title)
    }

    @Test
    fun `progress weighs milestones against elapsed time`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        // Half the time has passed.
        val goal = GoalFixtures.goal(start = GoalFixtures.today.minusDays(50), deadline = GoalFixtures.today.plusDays(50))
        GoalFixtures.milestone(goal, 1, listOf(DONE, IN_PROGRESS), weight = 1.0)
        GoalFixtures.milestone(goal, 2, listOf(DONE, NOT_STARTED, NOT_STARTED, NOT_STARTED), weight = 3.0)
        GoalFixtures.milestone(goal, 3, emptyList(), weight = 5.0)

        val view = client.get("/api/v1/goals/$goal") { bearerAuth(teacher) }.body<TeacherGoalView>()
        // (1 × 1/2 + 3 × 1/4) / 4 = 31.25 %
        assertEquals(31, view.progress.percent)
        assertEquals(50, view.progress.elapsedPercent)
        assertEquals(GoalTrackStatus.BEHIND, view.progress.status)
        assertEquals(listOf(50, 25, null), view.milestones.map { it.progressPercent })

        val ahead = GoalFixtures.goal(studentId = STUDENT, status = GoalStatus.ARCHIVED,
            start = GoalFixtures.today.minusDays(10), deadline = GoalFixtures.today.plusDays(90))
        GoalFixtures.milestone(ahead, 1, listOf(DONE, DONE, DONE, NOT_STARTED))
        val aheadView = client.get("/api/v1/goals/$ahead") { bearerAuth(teacher) }.body<TeacherGoalView>()
        assertEquals(75, aheadView.progress.percent)
        assertEquals(10, aheadView.progress.elapsedPercent)
        assertEquals(GoalTrackStatus.AHEAD, aheadView.progress.status)

        val summaries = client.get("/api/v1/goals") { bearerAuth(teacher) }.body<GoalPageResponse>().goals
        assertEquals(listOf(GoalTrackStatus.BEHIND, GoalTrackStatus.AHEAD), summaries.map { it.progress.status })
    }

    @Test
    fun `the student view is minimal and leaks no teacher data`() = testApp {
        startApplication()
        GoalFixtures.enableRoadmap()
        val client = createJsonClient(this)
        val goal = GoalFixtures.goal(teacherInput = true)
        GoalFixtures.milestone(goal, 1, listOf(DONE, DONE), done = true, title = "Sich vorstellen")
        val current = GoalFixtures.milestone(goal, 2, listOf(DONE, NOT_STARTED), title = "Familie")
        GoalFixtures.milestone(goal, 3, listOf(NOT_STARTED), title = "Einkaufen")

        val response = client.get("/api/v1/goals/my") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()

        val keys = mutableSetOf<String>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> keys += key; collect(value) }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        collect(Json.parseToJsonElement(text))
        val forbidden = setOf(
            "items", "grammarPoints", "wordFields", "teacherTips", "examPart", "levelNote", "interests", "materials",
            "exam", "lessonsPerWeek", "lessonMinutes", "weight", "topic", "teacherId", "studentId", "selfAssessment", "openMismatches",
        )
        assertEquals(emptySet(), keys intersect forbidden, text)
        for (secret in listOf("Geheimer Tipp", "Sprechen Teil 1", "Perfekt", "Punkt 1", "Familie\"", "LEHRER-NOTIZ", "Schach")) {
            assertTrue(secret !in text, "student JSON contains $secret: $text")
        }

        val view = response.body<MyGoalResponse>().goal
        assertNotNull(view)
        assertEquals("Goethe A1 bestehen", view.title)
        assertEquals(listOf(true, false, false), view.milestones.map { it.done })
        assertEquals(listOf(false, true, false), view.milestones.map { it.current })
        assertEquals(current.toString(), view.milestones.single { it.current }.id)
        assertEquals("Ich kann Familie.", view.currentCanDo)
        assertEquals("Ich kann Einkaufen.", view.nextCanDo)
        assertEquals(listOf("Sich vorstellen"), view.coveredTitles)
        assertEquals("Ich kann Sich vorstellen.", view.pendingSelfAssessment?.canDo)
        // (1 + 1/2 + 0) / 3
        assertEquals(50, view.progressPercent)
    }
}

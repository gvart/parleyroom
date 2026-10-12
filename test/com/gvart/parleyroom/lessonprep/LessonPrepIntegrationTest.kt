package com.gvart.parleyroom.lessonprep

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.STUDENT_2
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.ai.awaitJob
import com.gvart.parleyroom.ai.data.AiUsageTable
import com.gvart.parleyroom.ai.data.AiUseCase
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.llm.FakeLlmGateway
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.AiJobSummary
import com.gvart.parleyroom.ai.transfer.AiJobTargetType
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.goal.GoalFixtures
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.transfer.ItemStateChange
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lessonprep.data.LessonPlanStatus
import com.gvart.parleyroom.lessonprep.service.LessonHistoryService
import com.gvart.parleyroom.lessonprep.transfer.LessonHistoryResponse
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanContent
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanResponse
import com.gvart.parleyroom.lessonprep.transfer.NextLessonPrepResponse
import com.gvart.parleyroom.lessonprep.transfer.WrapUpRequest
import com.gvart.parleyroom.lessonprep.transfer.WrapUpResponse
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
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
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessonPrepIntegrationTest : IntegrationTest() {

    private val now: OffsetDateTime get() = OffsetDateTime.now()

    private suspend fun HttpClient.send(method: String, token: String, path: String, json: String? = null): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        return when (method) {
            "POST" -> post(path, block)
            "PATCH" -> patch(path, block)
            "DELETE" -> delete(path, block)
            else -> get(path, block)
        }
    }

    private suspend fun HttpClient.create(token: String, json: String): LessonPlanResponse {
        val response = send("POST", token, "/api/v1/lesson-plans", json)
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        return response.body()
    }

    /** Creates a plan and waits for its job; returns the plan as read afterwards. */
    private suspend fun HttpClient.prepared(token: String, json: String): LessonPlanResponse {
        val created = create(token, json)
        awaitJob(token, created.job!!.id)
        return send("GET", token, "/api/v1/lesson-plans/${created.id}").body()
    }

    private suspend fun HttpResponse.problem(status: HttpStatusCode, code: String): ProblemDetail {
        assertEquals(status, this.status, bodyAsText())
        return body<ProblemDetail>().also { assertEquals(code, it.code, bodyAsText()) }
    }

    private fun prepCalls() = FakeLlmGateway.received.filter { Prompts.section(it.messages.first().text, "task") == Prompts.TASK_LESSON_PREP }

    @Test
    fun `every route is 404 while the flag is off`() = testApp {
        startApplication()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val lesson = LessonPrepFixtures.lesson(now.minusDays(1))
        val plan = LessonPrepFixtures.plan(lesson)

        client.send("POST", teacher, "/api/v1/lesson-plans", """{"studentId":"$STUDENT","prepNote":"x"}""").problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.send("GET", teacher, "/api/v1/lesson-plans/$plan").problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.send("POST", teacher, "/api/v1/lessons/$lesson/wrap-up", "{}").problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.send("GET", teacher, "/api/v1/students/$STUDENT/lesson-history").problem(HttpStatusCode.NotFound, "NOT_FOUND")
        client.send("GET", teacher, "/api/v1/students/$STUDENT/next-lesson-prep").problem(HttpStatusCode.NotFound, "NOT_FOUND")
    }

    @Test
    fun `only the student's teacher or an admin gets in`() = testApp {
        startApplication()
        LessonPrepFixtures.enable()
        GoalFixtures.secondTeacher()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val admin = getAdminToken(client)
        val other = getToken(client, "teacher2@test.com")
        val lesson = LessonPrepFixtures.lesson(now.minusDays(1))
        val plan = LessonPrepFixtures.plan(lesson, coveredNote = "geheim")
        val body = """{"studentId":"$STUDENT","prepNote":"Notiz"}"""

        client.send("POST", student, "/api/v1/lesson-plans", body).problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        client.send("GET", student, "/api/v1/lesson-plans/$plan").problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        client.send("GET", student, "/api/v1/students/$STUDENT/lesson-history").problem(HttpStatusCode.Forbidden, "FORBIDDEN")
        client.send("POST", student, "/api/v1/lessons/$lesson/wrap-up", "{}").problem(HttpStatusCode.Forbidden, "FORBIDDEN")

        client.send("POST", other, "/api/v1/lesson-plans", body).problem(HttpStatusCode.NotFound, "STUDENT_NOT_FOUND")
        client.send("GET", other, "/api/v1/lesson-plans/$plan").problem(HttpStatusCode.NotFound, "LESSON_PLAN_NOT_FOUND")
        client.send("PATCH", other, "/api/v1/lesson-plans/$plan", "{}").problem(HttpStatusCode.NotFound, "LESSON_PLAN_NOT_FOUND")
        client.send("DELETE", other, "/api/v1/lesson-plans/$plan").problem(HttpStatusCode.NotFound, "LESSON_PLAN_NOT_FOUND")
        client.send("GET", other, "/api/v1/students/$STUDENT/lesson-history").problem(HttpStatusCode.NotFound, "STUDENT_NOT_FOUND")
        client.send("GET", other, "/api/v1/students/$STUDENT/next-lesson-prep").problem(HttpStatusCode.NotFound, "STUDENT_NOT_FOUND")
        client.send("POST", other, "/api/v1/lessons/$lesson/wrap-up", "{}").problem(HttpStatusCode.NotFound, "LESSON_NOT_FOUND")

        assertEquals(HttpStatusCode.OK, client.send("GET", teacher, "/api/v1/lesson-plans/$plan").status)
        assertEquals("geheim", client.send("GET", admin, "/api/v1/lesson-plans/$plan").body<LessonPlanResponse>().coveredNote)
        val history = client.send("GET", admin, "/api/v1/students/$STUDENT/lesson-history").body<LessonHistoryResponse>()
        assertEquals(listOf(lesson.toString()), history.items.map { it.lessonId })
        assertEquals(HttpStatusCode.NoContent, client.send("DELETE", admin, "/api/v1/lesson-plans/$plan").status)
    }

    @Test
    fun `prepares the next lesson from the note, the roadmap and the last lessons`() = testApp {
        startApplication()
        LessonPrepFixtures.enable()
        FakeLlmGateway.received.clear()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        transaction { UserTable.update({ UserTable.id eq STUDENT }) { it[level] = LanguageLevel.A1 } }
        val roadmap = LessonPrepFixtures.roadmap()
        val current = LessonPrepFixtures.items(roadmap.current)
        val last = LessonPrepFixtures.lesson(now.minusDays(3), notes = "Selbstvorstellung, Verbposition 2. Test Student hatte Probleme mit du.")
        LessonPrepFixtures.plan(last, content = LessonPlanContent(lessonGoal = "Sich vorstellen"), coveredNote = "du-Formen nur angefangen")
        LessonPrepFixtures.lesson(now.minusDays(2), status = LessonStatus.CANCELLED, notes = "ABGESAGT")
        LessonPrepFixtures.lesson(now.minusDays(20), notes = "Ganz alte Stunde 1")
        LessonPrepFixtures.lesson(now.minusDays(21), notes = "Ganz alte Stunde 2")
        LessonPrepFixtures.lesson(now.minusDays(22), notes = "Ganz alte Stunde 3")
        val next = LessonPrepFixtures.lesson(now.plusDays(2), status = LessonStatus.CONFIRMED, minutes = 45)
        LessonPrepFixtures.lesson(now.plusDays(9), status = LessonStatus.CONFIRMED)
        LessonPrepFixtures.lesson(now.plusDays(1), status = LessonStatus.CONFIRMED, student = STUDENT_2)

        val created = client.create(teacher, """{"studentId":"$STUDENT","prepNote":"Test hatte Probleme mit du-Formen"}""")
        assertEquals(next.toString(), created.lesson?.id, "linked to the next confirmed lesson")
        assertEquals(roadmap.goalId.toString(), created.goalId)
        assertNull(created.plan)
        assertEquals(GenerationJobKind.LESSON_PREP, created.job?.kind)
        assertEquals(created.id, created.job?.lessonPlanId)

        val job = client.awaitJob(teacher, created.job!!.id)
        assertEquals(GenerationJobStatus.SUCCEEDED, job.status, job.error?.toString())
        assertEquals("claude-sonnet-5-5", job.model)
        val plan = client.send("GET", teacher, "/api/v1/lesson-plans/${created.id}").body<LessonPlanResponse>()
        val content = assertNotNull(plan.plan)
        assertEquals(LessonPlanStatus.DRAFT, plan.status)
        assertEquals(45, content.timePlan.sumOf { it.minutes }, "fills the 45 minute lesson")
        assertEquals(current.take(2).map { it.toString() }, content.roadmapItemIds, "keys mapped back to item ids")
        assertEquals(current.take(2).map { it.toString() }, plan.roadmapItems.map { it.id })
        assertNotNull(content.suggestedHomework)
        assertTrue(transaction { AiUsageTable.selectAll().any { it[AiUsageTable.useCase] == AiUseCase.LESSON_PREP } })

        val request = prepCalls().single().messages.first().text
        // Privacy: names masked, no email; only the task's data.
        assertTrue("Test Student" !in request && "student@test.com" !in request, request)
        listOf("prep_note", "past_lessons", "self_assessment_hints", "goal").forEach { name ->
            assertTrue(Regex("\\bTest\\b").find(Prompts.section(request, name)!!) == null, "$name: $request")
        }
        assertEquals("[Name] hatte Probleme mit du-Formen", Prompts.section(request, "prep_note"))
        assertTrue("Minutes: 45" in Prompts.section(request, "lesson")!!)
        assertTrue("Student level: A1" in Prompts.section(request, "lesson")!!)
        // Roadmap: current + next milestone with keyed items, done items, the mismatch hint.
        val roadmapText = Prompts.section(request, "roadmap")!!
        assertTrue("Current milestone: Familie" in roadmapText && "Next milestone: Einkaufen" in roadmapText, roadmapText)
        assertTrue("I2 [GRAMMAR] Punkt 2 — IN_PROGRESS" in roadmapText, roadmapText)
        assertTrue("Begrüßung" !in roadmapText)
        assertEquals(2, Prompts.section(request, "done_items")!!.lines().size)
        val hints = Prompts.section(request, "self_assessment_hints")!!
        assertTrue("Begrüßung" in hints && "PARTLY" in hints && "[Name] kann die Zahlen" in hints, hints)
        // The last three lessons (cancelled left out), newest first, with plan goal and covered note.
        val past = Prompts.section(request, "past_lessons")!!
        assertTrue(past.startsWith("Lesson on ${now.minusDays(3).toLocalDate()}"), past)
        assertTrue("Notes: Selbstvorstellung, Verbposition 2. [Name] [Name] hatte Probleme mit du." in past, past)
        assertTrue("Planned goal: Sich vorstellen" in past && "Covered: du-Formen nur angefangen" in past, past)
        assertTrue("ABGESAGT" !in past && "Ganz alte Stunde 3" !in past && "Ganz alte Stunde 2" in past, past)
        assertEquals("Schach", Prompts.section(request, "interests"))
        assertTrue("Start Deutsch 1" in Prompts.section(request, "exam_reference")!!)
        assertEquals("All text in German.", Prompts.section(request, "language"))

        // The tray names the plan's student and lesson.
        val tray = client.send("GET", teacher, "/api/v1/ai/jobs").body<List<AiJobSummary>>().single { it.id == job.id }
        assertEquals(AiJobTargetType.LESSON_PLAN, tray.targetType)
        assertEquals(created.id, tray.lessonPlanId)
        assertEquals("Test Student", tray.studentName)

        // The next lesson already has a plan: a new prep links the one after, and the explicit lesson is taken.
        client.send("POST", teacher, "/api/v1/lesson-plans", """{"studentId":"$STUDENT","prepNote":"x","lessonId":"$next"}""")
            .problem(HttpStatusCode.Conflict, "LESSON_PLAN_EXISTS")
        val second = client.prepared(teacher, """{"studentId":"$STUDENT","prepNote":"Weiter","explainInRussian":true}""")
        assertTrue(second.lesson!!.id != next.toString())
        assertTrue("Russian" in Prompts.section(prepCalls().last().messages.first().text, "language")!!)

        val prep = client.send("GET", teacher, "/api/v1/students/$STUDENT/next-lesson-prep").body<NextLessonPrepResponse>()
        assertEquals(next.toString(), prep.lesson?.id)
        assertEquals(created.id, prep.plan?.id)
    }

    @Test
    fun `invalid output is retried once, then the job fails`() = testApp {
        startApplication()
        LessonPrepFixtures.enable()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        LessonPrepFixtures.roadmap()

        val retried = client.create(teacher, """{"studentId":"$STUDENT","prepNote":"[fake:invalid-once]"}""")
        val ok = client.awaitJob(teacher, retried.job!!.id)
        assertEquals(GenerationJobStatus.SUCCEEDED, ok.status)
        assertEquals(2, ok.attempts)
        // The retry was told about the unknown item key and the time plan.
        val feedback = Prompts.section(prepCalls().last().messages.last().text, Prompts.VALIDATION_ERRORS)!!
        assertTrue("/roadmapItemIds/2" in feedback && "/timePlan" in feedback, feedback)

        val failed = client.create(teacher, """{"studentId":"$STUDENT","prepNote":"[fake:invalid]"}""")
        val job = client.awaitJob(teacher, failed.job!!.id)
        assertEquals(GenerationJobStatus.FAILED, job.status)
        assertEquals("AI_OUTPUT_INVALID", job.error?.code)
        val plan = client.send("GET", teacher, "/api/v1/lesson-plans/${failed.id}").body<LessonPlanResponse>()
        assertNull(plan.plan)
        assertEquals("AI_OUTPUT_INVALID", plan.job?.error?.code)
    }

    @Test
    fun `patch validates the plan with field pointers`() = testApp {
        startApplication()
        LessonPrepFixtures.enable()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val roadmap = LessonPrepFixtures.roadmap()
        val item = LessonPrepFixtures.items(roadmap.current).first()
        val foreignItem = LessonPrepFixtures.items(GoalFixtures.milestone(GoalFixtures.goal(studentId = STUDENT_2), 1, listOf(RoadmapItemState.DONE))).first()
        val plan = client.prepared(teacher, """{"studentId":"$STUDENT","prepNote":"Notiz"}""")
        val path = "/api/v1/lesson-plans/${plan.id}"

        val invalid = client.send("PATCH", teacher, path, """{"status":"DONE","prepNote":"","plan":{"lessonGoal":"",
            "timePlan":[{"phase":"NAP","title":"x","minutes":0}],"vocabulary":[{"lemma":"Haus","article":"den","example":"x"}],
            "roadmapItemIds":["kein-uuid"]}}""").problem(HttpStatusCode.BadRequest, "VALIDATION_FAILED")
        val pointers = invalid.errors!!.mapNotNull { it.pointer }.toSet()
        assertEquals(
            setOf("/status", "/prepNote", "/plan/lessonGoal", "/plan/timePlan/0/phase", "/plan/timePlan/0/minutes",
                "/plan/vocabulary/0/article", "/plan/roadmapItemIds/0"),
            pointers,
        )

        val content = plan.plan!!
        client.send("PATCH", teacher, path, """{"plan":${jsonOf(content.copy(roadmapItemIds = listOf(foreignItem.toString())))}}""")
            .problem(HttpStatusCode.BadRequest, "LESSON_PLAN_ITEM_INVALID").also { assertEquals("/plan/roadmapItemIds/0", it.pointer) }
        client.send("PATCH", teacher, path, """{"lessonId":"${LessonPrepFixtures.lesson(now.plusDays(1), student = STUDENT_2)}"}""")
            .problem(HttpStatusCode.BadRequest, "LESSON_PLAN_LESSON_INVALID")

        val edited = content.copy(lessonGoal = "Neues Ziel", roadmapItemIds = listOf(item.toString()))
        val patched = client.send("PATCH", teacher, path, """{"status":"READY","lessonId":"","coveredNote":"Teil 1","plan":${jsonOf(edited)}}""")
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        val view = patched.body<LessonPlanResponse>()
        assertEquals("Neues Ziel", view.plan?.lessonGoal)
        assertEquals(LessonPlanStatus.READY, view.status)
        assertEquals("Teil 1", view.coveredNote)
        assertNull(view.lesson)
        assertEquals(listOf(item.toString()), view.roadmapItems.map { it.id })
    }

    @Test
    fun `wrap-up saves what was covered, checks items and hands over the homework prompt`() = testApp {
        startApplication()
        LessonPrepFixtures.enable()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val roadmap = LessonPrepFixtures.roadmap()
        val (a, b) = LessonPrepFixtures.items(roadmap.current)
        val lesson = LessonPrepFixtures.lesson(now.plusHours(1), status = LessonStatus.CONFIRMED)
        val plan = client.prepared(teacher, """{"studentId":"$STUDENT","prepNote":"Notiz"}""")
        assertEquals(lesson.toString(), plan.lesson?.id)

        val body = """{"coveredNote":"du-Formen geschafft","itemStates":[{"itemId":"$a","state":"DONE"},{"itemId":"$b","state":"IN_PROGRESS"}]}"""
        val first = client.send("POST", teacher, "/api/v1/lessons/$lesson/wrap-up", body)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val wrapped = first.body<WrapUpResponse>()
        val again = client.send("POST", teacher, "/api/v1/lessons/$lesson/wrap-up", body).body<WrapUpResponse>()
        assertEquals(LessonPlanStatus.DONE, wrapped.plan.status)
        assertEquals("du-Formen geschafft", again.plan.coveredNote)
        assertEquals(wrapped.suggestedHomework, again.suggestedHomework)
        assertEquals(plan.plan!!.suggestedHomework, wrapped.suggestedHomework)
        transaction {
            val rows = RoadmapItemTable.selectAll().where { RoadmapItemTable.milestoneId eq roadmap.current }.associateBy { it[RoadmapItemTable.id].value }
            assertEquals(RoadmapItemState.DONE, rows.getValue(a)[RoadmapItemTable.state])
            assertEquals(RoadmapItemState.IN_PROGRESS, rows.getValue(b)[RoadmapItemTable.state])
            assertEquals(lesson, rows.getValue(a)[RoadmapItemTable.lessonId]?.value)
        }
        client.send("POST", teacher, "/api/v1/lesson-plans/${plan.id}/regenerate", "{}").problem(HttpStatusCode.Conflict, "LESSON_PLAN_DONE")

        // The suggested prompt feeds the existing follow-up homework generator as is.
        val draft = client.post("/api/v1/lessons/$lesson/draft-bundles") {
            bearerAuth(teacher); contentType(ContentType.Application.Json)
            setBody(GenerateDraftRequest(prompt = wrapped.suggestedHomework!!.prompt, kinds = listOf(DraftKind.HOMEWORK)))
        }
        assertEquals(HttpStatusCode.Accepted, draft.status, draft.bodyAsText())
        assertEquals(GenerationJobStatus.SUCCEEDED, client.awaitJob(teacher, draft.body<DraftBundleResponse>().job!!.id).status)

        // Unknown items: nothing written; a 1:1 lesson without a plan gets one.
        val bare = LessonPrepFixtures.lesson(now.minusDays(1))
        client.send("POST", teacher, "/api/v1/lessons/$bare/wrap-up", """{"itemStates":[{"itemId":"${UUID.randomUUID()}","state":"DONE"}]}""")
            .problem(HttpStatusCode.NotFound, "ROADMAP_ITEM_NOT_FOUND")
        client.send("POST", teacher, "/api/v1/lessons/$bare/wrap-up", """{"itemStates":[{"itemId":"$a","state":"BAD"}]}""")
            .problem(HttpStatusCode.BadRequest, "VALIDATION_FAILED").also { assertEquals("/itemStates", it.errors!!.single().pointer) }
        val created = client.send("POST", teacher, "/api/v1/lessons/$bare/wrap-up", """{"coveredNote":"Ohne Vorbereitung"}""").body<WrapUpResponse>()
        assertEquals(LessonPlanStatus.DONE, created.plan.status)
        assertEquals(bare.toString(), created.plan.lesson?.id)
        assertNull(created.suggestedHomework)
    }

    @Test
    fun `regenerate keeps the lesson link and takes a new note`() = testApp {
        startApplication()
        LessonPrepFixtures.enable()
        FakeLlmGateway.received.clear()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val lesson = LessonPrepFixtures.lesson(now.plusDays(1), status = LessonStatus.CONFIRMED, minutes = 90)
        val plan = client.prepared(teacher, """{"studentId":"$STUDENT","prepNote":"Erste Notiz"}""")
        assertEquals(90, plan.plan!!.timePlan.sumOf { it.minutes })
        assertEquals(emptyList(), plan.plan.roadmapItemIds, "no goal, no items")
        assertEquals("(none)", Prompts.section(prepCalls().last().messages.first().text, "goal"))

        val response = client.send("POST", teacher, "/api/v1/lesson-plans/${plan.id}/regenerate", """{"prepNote":"Zweite Notiz"}""")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        client.awaitJob(teacher, response.body<LessonPlanResponse>().job!!.id)
        val again = client.send("GET", teacher, "/api/v1/lesson-plans/${plan.id}").body<LessonPlanResponse>()
        assertEquals(lesson.toString(), again.lesson?.id)
        assertEquals("Zweite Notiz", again.prepNote)
        assertEquals("Zweite Notiz", Prompts.section(prepCalls().last().messages.first().text, "prep_note"))
        assertEquals(2, prepCalls().size)
    }

    @Test
    fun `lesson history is paginated, complete and only the student's`() = testApp {
        startApplication()
        LessonPrepFixtures.enable()
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val roadmap = LessonPrepFixtures.roadmap()
        val (a, b) = LessonPrepFixtures.items(roadmap.current)
        val lessons = (1..5).map { LessonPrepFixtures.lesson(now.minusDays(it.toLong()), notes = "Notizen $it", title = "Stunde $it") }
        val next = LessonPrepFixtures.lesson(now.plusDays(3), status = LessonStatus.CONFIRMED)
        LessonPrepFixtures.lesson(now.minusDays(1), status = LessonStatus.REQUEST)
        LessonPrepFixtures.plan(lessons[0], goalId = roadmap.goalId, content = LessonPlanContent(lessonGoal = "Ziel 1"), coveredNote = "Geschafft 1")
        LessonPrepFixtures.plan(next, status = LessonPlanStatus.READY, content = LessonPlanContent(lessonGoal = "Ziel next"))
        LessonPrepFixtures.checkItem(a, lessons[0])
        LessonPrepFixtures.checkItem(b, lessons[0], RoadmapItemState.IN_PROGRESS)
        val homework = LessonPrepFixtures.homework(lessons[0], "Hausaufgabe 1")
        // Another student of the same teacher: their lessons, items and homework never show up.
        val foreign = LessonPrepFixtures.lesson(now.minusHours(5), student = STUDENT_2, notes = "FREMD")
        LessonPrepFixtures.homework(lessons[0], "Fremde Hausaufgabe", student = STUDENT_2)
        val foreignItem = LessonPrepFixtures.items(GoalFixtures.milestone(GoalFixtures.goal(studentId = STUDENT_2), 1, listOf(RoadmapItemState.DONE))).first()
        LessonPrepFixtures.checkItem(foreignItem, lessons[0])

        val page1 = client.send("GET", teacher, "/api/v1/students/$STUDENT/lesson-history?pageSize=2").body<LessonHistoryResponse>()
        assertEquals(5, page1.total)
        assertEquals(lessons.take(2).map { it.toString() }, page1.items.map { it.lessonId })
        assertEquals(next.toString(), page1.nextLesson?.lessonId)
        assertEquals("Ziel next", page1.nextLesson?.plan?.lessonGoal)
        val first = page1.items.first()
        assertEquals("Notizen 1", first.rawNotes)
        assertEquals("Ziel 1", first.plan?.lessonGoal)
        assertEquals(LessonPlanStatus.DONE, first.plan?.status)
        assertEquals("Geschafft 1", first.coveredNote)
        assertEquals(listOf(a.toString() to RoadmapItemState.DONE, b.toString() to RoadmapItemState.IN_PROGRESS), first.roadmapItems.map { it.id to it.state })
        assertEquals(listOf(homework.toString() to "Hausaufgabe 1"), first.homework.map { it.id to it.title })

        val page3 = client.send("GET", teacher, "/api/v1/students/$STUDENT/lesson-history?page=3&pageSize=2").body<LessonHistoryResponse>()
        assertEquals(listOf(lessons[4].toString()), page3.items.map { it.lessonId })
        assertEquals(next.toString(), page3.nextLesson?.lessonId)

        val admin = getAdminToken(client)
        val other = client.send("GET", admin, "/api/v1/students/$STUDENT_2/lesson-history").body<LessonHistoryResponse>()
        assertEquals(listOf(foreign.toString()), other.items.map { it.lessonId })
        assertTrue(other.items.single().homework.isEmpty())
    }

    @Test
    fun `a history page runs a fixed number of queries`() = testApp {
        startApplication()
        val roadmap = LessonPrepFixtures.roadmap()
        val items = LessonPrepFixtures.items(roadmap.current)
        val lessons = (1..6).map { LessonPrepFixtures.lesson(now.minusDays(it.toLong()), notes = "N$it") }
        lessons.forEachIndexed { i, lesson ->
            LessonPrepFixtures.plan(lesson, coveredNote = "C$i")
            LessonPrepFixtures.homework(lesson, "H$i")
            items.getOrNull(i)?.let { LessonPrepFixtures.checkItem(it, lesson) }
        }
        LessonPrepFixtures.lesson(now.plusDays(1), status = LessonStatus.CONFIRMED)
        val service = LessonHistoryService()
        val principal = UserPrincipal(TEACHER, "teacher@test.com", UserRole.TEACHER)

        fun count(pageSize: Int): Int = transaction {
            var statements = 0
            registerInterceptor(object : StatementInterceptor {
                override fun beforeExecution(transaction: Transaction, context: StatementContext) { statements++ }
            })
            assertEquals(pageSize, service.history(STUDENT, principal, PageRequest(1, pageSize)).items.size)
            statements
        }
        val small = count(1)
        assertTrue(small >= 6, "statements are counted ($small)")
        assertEquals(small, count(6))
    }

    private fun jsonOf(content: LessonPlanContent) = kotlinx.serialization.json.Json.encodeToString(LessonPlanContent.serializer(), content)
}

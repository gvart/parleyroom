package com.gvart.parleyroom.homework

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.homework.HomeworkFixtures.EC_1
import com.gvart.parleyroom.homework.HomeworkFixtures.EC_BLOCK
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_1
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_2
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_BLOCK
import com.gvart.parleyroom.homework.HomeworkFixtures.MC_1
import com.gvart.parleyroom.homework.HomeworkFixtures.MC_BLOCK
import com.gvart.parleyroom.homework.HomeworkFixtures.OPT_BIN
import com.gvart.parleyroom.homework.HomeworkFixtures.Q_OPEN
import com.gvart.parleyroom.homework.HomeworkFixtures.Q_TF
import com.gvart.parleyroom.homework.HomeworkFixtures.READING_BLOCK
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkOutcome
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.transfer.AnswersSavedResponse
import com.gvart.parleyroom.homework.transfer.AssignmentPageResponse
import com.gvart.parleyroom.homework.transfer.AssignmentResponse
import com.gvart.parleyroom.homework.transfer.HomeworkCountsResponse
import com.gvart.parleyroom.homework.transfer.HomeworkPageResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.UnitResponse
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeworkIntegrationTest : IntegrationTest() {

    // ---- helpers ----

    private suspend fun send(
        client: HttpClient, method: String, path: String, token: String, body: String? = null,
    ): HttpResponse {
        val configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            bearerAuth(token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> client.get(path, configure)
            "POST" -> client.post(path, configure)
            "PUT" -> client.put(path, configure)
            "PATCH" -> client.patch(path, configure)
            "DELETE" -> client.delete(path, configure)
            else -> error(method)
        }
    }

    private suspend fun HttpResponse.code(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    private suspend fun HttpResponse.pointer(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["pointer"]?.jsonPrimitive?.content

    private suspend fun assignDocument(client: HttpClient, teacher: String, extra: String = ""): AssignmentResponse {
        val documentId = HomeworkFixtures.createExerciseDocument(client, teacher)
        val response = HomeworkFixtures.postAssignment(
            client, teacher,
            """{ "title": "Hausaufgabe Haushalt", "instructions": "Bis Freitag", "dueDate": "2026-10-02",
                 "studentIds": ["$STUDENT_ID"] $extra,
                 "items": [{ "kind": "DOCUMENT", "documentId": "$documentId" },
                           { "kind": "TASK", "title": "Sprich über deine Wohnung", "responseType": "AUDIO" }] }""",
        )
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private fun docUnit(item: String, block: String, ref: String, answer: String) =
        """{ "assignmentItemId": "$item", "blockId": "$block", "itemId": "$ref", "answer": $answer }"""

    /** Answers every document unit: GAP_1 with a case slip, GAP_2 wrong, MC right, TF right, OPEN + EC texts. */
    private fun fullAnswers(docItem: String) = """{ "answers": [
        ${docUnit(docItem, GAP_BLOCK, GAP_1, """{"gaps":["musst","wäsche"]}""")},
        ${docUnit(docItem, GAP_BLOCK, GAP_2, """{"gaps":["fährt"]}""")},
        ${docUnit(docItem, MC_BLOCK, MC_1, """{"optionIds":["$OPT_BIN"]}""")},
        ${docUnit(docItem, READING_BLOCK, Q_TF, """{"isTrue":true}""")},
        ${docUnit(docItem, READING_BLOCK, Q_OPEN, """{"text":"Er spült."}""")},
        ${docUnit(docItem, EC_BLOCK, EC_1, """{"text":"Darum musst du dich kümmern"}""")}
    ] }"""

    private suspend fun homework(client: HttpClient, id: String, token: String): HomeworkResponse =
        send(client, "GET", "/api/v1/homework/$id", token).also { assertEquals(HttpStatusCode.OK, it.status) }.body()

    private fun HomeworkResponse.unit(ref: String): UnitResponse = units.single { it.itemId == ref }

    private suspend fun notifications(client: HttpClient, token: String): List<NotificationType> =
        send(client, "GET", "/api/v1/notifications", token).body<NotificationPageResponse>().notifications.map { it.type }

    private fun linkStudent2() = transaction {
        exec(
            "INSERT INTO teacher_students (teacher_id, student_id, lesson_types, status, started_at) " +
                "VALUES ('$TEACHER_ID', '$STUDENT_2_ID', '{ONE_ON_ONE}', 'ACTIVE', now())"
        )
    }

    // ---- assigning ----

    @Test
    fun `assigning to a group expands to its members and notifies each student`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        linkStudent2()
        val group = send(client, "POST", "/api/v1/groups", teacher,
            """{ "name": "Sprechclub", "type": "SPEECH", "studentIds": ["$STUDENT_ID", "$STUDENT_2_ID"] }""")
        val groupId = Json.parseToJsonElement(group.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val response = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "Club", "studentIds": ["$STUDENT_ID"], "groupIds": ["$groupId"],
                 "items": [{ "kind": "TASK", "title": "Aufnahme", "responseType": "AUDIO" }] }""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val assignment = response.body<AssignmentResponse>()
        assertEquals(setOf(STUDENT_ID, STUDENT_2_ID), assignment.homework.map { it.student.id }.toSet())
        assertEquals(listOf(groupId), assignment.groupIds)
        assertTrue(assignment.homework.all { it.status == HomeworkStatus.OPEN && it.totalUnits == 1 })

        assertEquals(listOf(NotificationType.HOMEWORK_ASSIGNED), notifications(client, getStudentToken(client)))
        assertEquals(listOf(NotificationType.HOMEWORK_ASSIGNED), notifications(client, getStudent2Token(client)))
    }

    @Test
    fun `assignment validation errors`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val task = """{ "kind": "TASK", "title": "T", "responseType": "TEXT" }"""

        val noStudents = HomeworkFixtures.postAssignment(client, teacher, """{ "title": "x", "items": [$task] }""")
        assertEquals(HttpStatusCode.BadRequest, noStudents.status)
        assertEquals("ASSIGNMENT_NO_STUDENTS", noStudents.code())

        val unlinked = HomeworkFixtures.postAssignment(client, teacher, """{ "title": "x", "studentIds": ["$STUDENT_2_ID"], "items": [$task] }""")
        assertEquals("STUDENT_NOT_LINKED", unlinked.code())

        val noResponseType = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "x", "studentIds": ["$STUDENT_ID"], "items": [$task, { "kind": "TASK", "title": "T" }] }""")
        assertEquals(HttpStatusCode.BadRequest, noResponseType.status)
        assertEquals("HOMEWORK_ITEM_INVALID", noResponseType.code())
        assertEquals("/items/1/responseType", noResponseType.pointer())

        val plainDoc = HomeworkFixtures.createExerciseDocument(client, teacher,
            """[{ "id": "30000000-0000-0000-0000-000000000001", "type": "heading", "interactive": true, "text": "Nur Text", "level": 1 }]""")
        val nothingToAnswer = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "x", "studentIds": ["$STUDENT_ID"], "items": [{ "kind": "DOCUMENT", "documentId": "$plainDoc" }] }""")
        assertEquals("HOMEWORK_ITEM_INVALID", nothingToAnswer.code())
        assertEquals("/items/0/documentId", nothingToAnswer.pointer())

        val unknownDoc = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "x", "studentIds": ["$STUDENT_ID"], "items": [{ "kind": "DOCUMENT", "documentId": "$STUDENT_ID" }] }""")
        assertEquals(HttpStatusCode.NotFound, unknownDoc.status)
        assertEquals("DOCUMENT_NOT_FOUND", unknownDoc.code())

        val unknownField = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "x", "studentIds": ["$STUDENT_ID"], "items": [$task], "category": "WRITING" }""")
        assertEquals(HttpStatusCode.BadRequest, unknownField.status)

        val blankTitle = HomeworkFixtures.postAssignment(client, teacher, """{ "title": " ", "studentIds": ["$STUDENT_ID"], "items": [$task] }""")
        assertEquals("VALIDATION_FAILED", blankTitle.code())

        val asStudent = HomeworkFixtures.postAssignment(client, getStudentToken(client),
            """{ "title": "x", "studentIds": ["$STUDENT_ID"], "items": [$task] }""")
        assertEquals(HttpStatusCode.Forbidden, asStudent.status)
    }

    @Test
    fun `document items are snapshots, later document edits do not change them`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val assignment = assignDocument(client, teacher)
        val docItem = assignment.items.first()
        assertEquals(6 + 1, assignment.homework.single().totalUnits)
        assertEquals("Haushalt – Übungen", docItem.title)

        send(client, "PUT", "/api/v1/documents/${docItem.documentId}", teacher,
            """{ "title": "Neu", "audience": "STUDENT", "blocks": [], "revision": ${docItem.documentRevision} }""")
            .also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        val again = send(client, "GET", "/api/v1/assignments/${assignment.id}", teacher).body<AssignmentResponse>()
        assertEquals(docItem.blocks, again.items.first().blocks)
        assertTrue(again.items.first().blocks.toString().contains("solution"), "teachers see the answer key")
    }

    @Test
    fun `teacher lists, updates and deletes assignments`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val assignment = HomeworkFixtures.assignTextTask(client, teacher, STUDENT_ID)

        val page = send(client, "GET", "/api/v1/assignments?studentId=$STUDENT_ID", teacher).body<AssignmentPageResponse>()
        assertEquals(listOf(assignment.id), page.assignments.map { it.id })
        assertEquals(1, page.assignments.single().statusCounts[HomeworkStatus.OPEN])
        assertEquals(1, page.assignments.single().studentCount)

        val patched = send(client, "PATCH", "/api/v1/assignments/${assignment.id}", teacher, """{ "dueDate": "2026-11-01", "title": "Neu" }""")
            .body<AssignmentResponse>()
        assertEquals("2026-11-01", patched.dueDate)
        assertEquals("Neu", patched.title)
        val cleared = send(client, "PATCH", "/api/v1/assignments/${assignment.id}", teacher, """{ "clearDueDate": true }""").body<AssignmentResponse>()
        assertNull(cleared.dueDate)

        assertEquals(HttpStatusCode.Forbidden, send(client, "GET", "/api/v1/assignments", getStudentToken(client)).status)

        assertEquals(HttpStatusCode.NoContent, send(client, "DELETE", "/api/v1/assignments/${assignment.id}", teacher).status)
        val homeworkId = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        val gone = send(client, "GET", "/api/v1/homework/$homeworkId", getStudentToken(client))
        assertEquals(HttpStatusCode.NotFound, gone.status)
        assertEquals("HOMEWORK_NOT_FOUND", gone.code())
    }

    // ---- drafts ----

    @Test
    fun `draft answers merge partially and idempotently, teacher sees progress but no content`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assignDocument(client, teacher)
        val docItem = assignment.items.first().id
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)

        val body = """{ "answers": [${docUnit(docItem, GAP_BLOCK, GAP_1, """{"gaps":["musst",""]}""")}] }"""
        val first = send(client, "PUT", "/api/v1/homework/$id/answers", student, body)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals(1, first.body<AnswersSavedResponse>().answeredUnits)
        assertEquals(7, first.body<AnswersSavedResponse>().totalUnits)

        // Same body again: no change. A second unit is merged, the first one is untouched.
        send(client, "PUT", "/api/v1/homework/$id/answers", student, body)
        val second = send(client, "PUT", "/api/v1/homework/$id/answers", student,
            """{ "answers": [${docUnit(docItem, MC_BLOCK, MC_1, """{"optionIds":["$OPT_BIN"]}""")}] }""").body<AnswersSavedResponse>()
        assertEquals(2, second.answeredUnits)
        assertNotNull(second.lastSavedAt)

        val mine = homework(client, id, student)
        assertEquals("""{"gaps":["musst",""]}""", mine.unit(GAP_1).answer.toString())

        val teacherView = homework(client, id, teacher)
        assertEquals(2, teacherView.answeredUnits)
        assertNotNull(teacherView.lastSavedAt)
        assertTrue(teacherView.units.all { it.answer == null }, "drafts are private")

        // null removes an answer
        val removed = send(client, "PUT", "/api/v1/homework/$id/answers", student,
            """{ "answers": [${docUnit(docItem, MC_BLOCK, MC_1, "null")}] }""").body<AnswersSavedResponse>()
        assertEquals(1, removed.answeredUnits)
    }

    @Test
    fun `invalid answers are rejected with a pointer`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assignDocument(client, teacher)
        val docItem = assignment.items.first().id
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)

        val wrongShape = send(client, "PUT", "/api/v1/homework/$id/answers", student,
            """{ "answers": [{ "assignmentItemId": "$docItem", "blockId": "$GAP_BLOCK", "itemId": "$GAP_2", "answer": {"text":"geht"} }] }""")
        assertEquals(HttpStatusCode.BadRequest, wrongShape.status)
        assertEquals("HOMEWORK_ANSWER_INVALID", wrongShape.code())
        assertEquals("/answers/0/answer/text", wrongShape.pointer())

        val unknownUnit = send(client, "PUT", "/api/v1/homework/$id/answers", student,
            """{ "answers": [{ "assignmentItemId": "$docItem", "blockId": "$GAP_BLOCK", "itemId": "$MC_1", "answer": {"gaps":["x"]} }] }""")
        assertEquals("HOMEWORK_ITEM_INVALID", unknownUnit.code())
        assertEquals("/answers/0", unknownUnit.pointer())

        val asTeacher = send(client, "PUT", "/api/v1/homework/$id/answers", teacher, """{ "answers": [] }""")
        assertEquals(HttpStatusCode.Forbidden, asTeacher.status)

        val otherStudent = send(client, "PUT", "/api/v1/homework/$id/answers", getStudent2Token(client), """{ "answers": [] }""")
        assertEquals(HttpStatusCode.NotFound, otherStudent.status)
    }

    // ---- submit, auto-check, visibility ----

    @Test
    fun `submit auto-checks closed units, locks answers and hides results until review`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assignDocument(client, teacher)
        val docItem = assignment.items.first().id
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        send(client, "PUT", "/api/v1/homework/$id/answers", student, fullAnswers(docItem)).also { assertEquals(HttpStatusCode.OK, it.status) }

        val beforeSubmit = send(client, "GET", "/api/v1/homework/$id", student).bodyAsText()
        assertFalse(beforeSubmit.contains("solution"), "no answer key before submit")

        val submitted = send(client, "POST", "/api/v1/homework/$id/submit", student)
        assertEquals(HttpStatusCode.OK, submitted.status, submitted.bodyAsText())
        val studentView = submitted.body<HomeworkResponse>()
        assertEquals(HomeworkStatus.SUBMITTED, studentView.status)
        assertEquals(1, studentView.attempt)
        assertNull(studentView.summary)
        assertTrue(studentView.units.all { it.autoResult == null && it.correct == null && it.caseMismatch == null })
        assertFalse(send(client, "GET", "/api/v1/homework/$id", student).bodyAsText().contains("solution"), "no answer key after submit")

        // Teacher sees everything once submitted.
        val teacherView = homework(client, id, teacher)
        assertEquals(AutoResult.CORRECT, teacherView.unit(GAP_1).autoResult)
        assertEquals(true, teacherView.unit(GAP_1).caseMismatch)
        assertEquals(listOf("CORRECT", "CASE_MISMATCH"), teacherView.unit(GAP_1).gapResults)
        assertEquals(AutoResult.INCORRECT, teacherView.unit(GAP_2).autoResult)
        assertEquals(AutoResult.CORRECT, teacherView.unit(MC_1).autoResult)
        assertEquals(AutoResult.CORRECT, teacherView.unit(Q_TF).autoResult)
        assertEquals(AutoResult.PENDING_REVIEW, teacherView.unit(Q_OPEN).autoResult)
        assertEquals(AutoResult.PENDING_REVIEW, teacherView.unit(EC_1).autoResult)
        assertNull(teacherView.unit(EC_1).correct)
        val summary = assertNotNull(teacherView.summary)
        assertEquals(3, summary.closedCorrect)
        assertEquals(4, summary.closedTotal)
        assertEquals(2, summary.pendingReview)
        assertEquals(1, summary.unanswered) // the audio task
        assertEquals("""{"gaps":["musst","wäsche"]}""", teacherView.unit(GAP_1).answer.toString())

        // Locked.
        val edit = send(client, "PUT", "/api/v1/homework/$id/answers", student, fullAnswers(docItem))
        assertEquals(HttpStatusCode.Conflict, edit.status)
        assertEquals("SUBMISSION_LOCKED", edit.code())
        assertEquals("SUBMISSION_LOCKED", send(client, "POST", "/api/v1/homework/$id/submit", student).code())

        assertEquals(listOf(NotificationType.HOMEWORK_SUBMITTED), notifications(client, teacher))
    }

    @Test
    fun `review, return for rework, resubmit and done`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assignDocument(client, teacher)
        val docItem = assignment.items.first().id
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        send(client, "PUT", "/api/v1/homework/$id/answers", student, fullAnswers(docItem))
        send(client, "POST", "/api/v1/homework/$id/submit", student)

        // Draft review: saved, invisible to the student while SUBMITTED.
        val draft = send(client, "PUT", "/api/v1/homework/$id/review", teacher,
            """{ "feedback": "Fast gut", "units": [
                 { "assignmentItemId": "$docItem", "blockId": "$GAP_BLOCK", "itemId": "$GAP_2", "correct": false, "comment": "gehen, nicht fahren" },
                 { "assignmentItemId": "$docItem", "blockId": "$EC_BLOCK", "itemId": "$EC_1", "correct": true, "comment": "Super" } ] }""")
        assertEquals(HttpStatusCode.OK, draft.status, draft.bodyAsText())
        assertEquals("Fast gut", draft.body<HomeworkResponse>().feedback)
        val hidden = homework(client, id, student)
        assertNull(hidden.feedback)
        assertTrue(hidden.units.all { it.comment == null })

        // Return for rework: OPEN again, feedback and comments visible, still no results or solutions.
        val returned = send(client, "POST", "/api/v1/homework/$id/review", teacher,
            """{ "feedback": "Bitte Lücke 2 korrigieren", "outcome": "RETURNED", "units": [] }""").body<HomeworkResponse>()
        assertEquals(HomeworkStatus.OPEN, returned.status)
        assertEquals(HomeworkOutcome.RETURNED, returned.lastOutcome)
        val reworking = homework(client, id, student)
        assertEquals(HomeworkOutcome.RETURNED, reworking.lastOutcome)
        assertNotNull(reworking.returnedAt)
        assertEquals("Bitte Lücke 2 korrigieren", reworking.feedback)
        assertEquals("gehen, nicht fahren", reworking.unit(GAP_2).comment)
        assertNull(reworking.unit(GAP_2).correct)
        assertFalse(send(client, "GET", "/api/v1/homework/$id", student).bodyAsText().contains("solution"))
        assertTrue(homework(client, id, teacher).units.all { it.answer == null }, "rework is a draft again")
        assertEquals(1, send(client, "GET", "/api/v1/homework/counts", student).body<HomeworkCountsResponse>().returned)

        // Fix GAP_2 and resubmit: its old verdict is cleared, the unchanged EC verdict stays.
        send(client, "PUT", "/api/v1/homework/$id/answers", student,
            """{ "answers": [${docUnit(docItem, GAP_BLOCK, GAP_2, """{"gaps":["geht"]}""")}] }""")
        val resubmitted = send(client, "POST", "/api/v1/homework/$id/submit", student).body<HomeworkResponse>()
        assertEquals(2, resubmitted.attempt)
        val afterResubmit = homework(client, id, teacher)
        assertEquals(AutoResult.CORRECT, afterResubmit.unit(GAP_2).autoResult)
        assertNull(afterResubmit.unit(GAP_2).teacherCorrect)
        assertNull(afterResubmit.unit(GAP_2).comment)
        assertEquals(true, afterResubmit.unit(EC_1).teacherCorrect)
        assertEquals("Super", afterResubmit.unit(EC_1).comment)

        // Reviewed: the student now sees results, the solution and the teacher's verdicts.
        val reviewed = send(client, "POST", "/api/v1/homework/$id/review", teacher,
            """{ "feedback": "Gut gemacht", "outcome": "REVIEWED", "units": [
                 { "assignmentItemId": "$docItem", "blockId": "$READING_BLOCK", "itemId": "$Q_OPEN", "correct": true, "comment": null } ] }""")
        assertEquals(HttpStatusCode.OK, reviewed.status, reviewed.bodyAsText())
        val result = homework(client, id, student)
        assertEquals(HomeworkStatus.REVIEWED, result.status)
        assertEquals("Gut gemacht", result.feedback)
        assertEquals(true, result.unit(GAP_1).correct)
        assertEquals(true, result.unit(GAP_1).caseMismatch)
        assertEquals(true, result.unit(Q_OPEN).correct)
        assertEquals(true, result.unit(EC_1).correct)
        assertNotNull(result.summary)
        assertTrue(send(client, "GET", "/api/v1/homework/$id", student).bodyAsText().contains("\"solution\""))

        // Draft reviews only on SUBMITTED; DONE is final.
        assertEquals("HOMEWORK_INVALID_STATE", send(client, "PUT", "/api/v1/homework/$id/review", teacher, """{ "feedback": "x" }""").code())
        val done = send(client, "POST", "/api/v1/homework/$id/review", teacher, """{ "feedback": "Gut gemacht", "outcome": "DONE" }""").body<HomeworkResponse>()
        assertEquals(HomeworkStatus.DONE, done.status)
        assertNotNull(done.doneAt)
        val again = send(client, "POST", "/api/v1/homework/$id/review", teacher, """{ "feedback": null, "outcome": "REVIEWED" }""")
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("HOMEWORK_INVALID_STATE", again.code())

        // Students cannot review; the notifications tell the story.
        assertEquals(HttpStatusCode.Forbidden, send(client, "POST", "/api/v1/homework/$id/review", student, """{ "outcome": "DONE" }""").status)
        assertEquals(
            listOf(NotificationType.HOMEWORK_REVIEWED, NotificationType.HOMEWORK_RETURNED, NotificationType.HOMEWORK_ASSIGNED),
            notifications(client, student),
        )
    }

    @Test
    fun `done directly from submitted notifies the student once`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = HomeworkFixtures.assignTextTask(client, teacher, STUDENT_ID)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        val item = assignment.items.single().id
        send(client, "PUT", "/api/v1/homework/$id/answers", student,
            """{ "answers": [{ "assignmentItemId": "$item", "answer": { "text": "Am Wochenende war ich im Park." } }] }""")
        send(client, "POST", "/api/v1/homework/$id/submit", student)

        val review = send(client, "POST", "/api/v1/homework/$id/review", teacher,
            """{ "outcome": "DONE", "feedback": null, "units": [{ "assignmentItemId": "$item", "correct": true, "comment": "Prima" }] }""")
        assertEquals(HttpStatusCode.OK, review.status, review.bodyAsText())
        val view = homework(client, id, student)
        assertEquals(HomeworkStatus.DONE, view.status)
        assertNotNull(view.reviewedAt)
        assertEquals(true, view.units.single().correct)
        assertEquals("Prima", view.units.single().comment)
        assertEquals(listOf(NotificationType.HOMEWORK_REVIEWED, NotificationType.HOMEWORK_ASSIGNED), notifications(client, student))
    }

    // ---- lists and counts ----

    @Test
    fun `lists filter by status and sort the review queue, counts match`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
        val overdue = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "Alt", "dueDate": "${today.minusDays(3)}", "studentIds": ["$STUDENT_ID"],
                 "items": [{ "kind": "TASK", "title": "T", "responseType": "TEXT" }] }""").body<AssignmentResponse>()
        val soon = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "Bald", "dueDate": "${today.plusDays(1)}", "studentIds": ["$STUDENT_ID"],
                 "items": [{ "kind": "TASK", "title": "T", "responseType": "TEXT" }] }""").body<AssignmentResponse>()
        val later = HomeworkFixtures.assignTextTask(client, teacher, STUDENT_ID)
        send(client, "POST", "/api/v1/homework/${HomeworkFixtures.homeworkIdOf(later, STUDENT_ID)}/submit", student)

        val queue = send(client, "GET", "/api/v1/homework?status=SUBMITTED&sort=submitted", teacher).body<HomeworkPageResponse>()
        assertEquals(listOf(later.id), queue.homework.map { it.assignmentId })
        assertEquals("Test", queue.homework.single().student.firstName)

        val mine = send(client, "GET", "/api/v1/homework?status=OPEN", student).body<HomeworkPageResponse>()
        assertEquals(listOf(overdue.id, soon.id), mine.homework.map { it.assignmentId }, "due date ascending")
        assertEquals("Teacher", mine.homework.first().teacher.lastName)

        val dueFilter = send(client, "GET", "/api/v1/homework?dueAfter=$today", student).body<HomeworkPageResponse>()
        assertEquals(listOf(soon.id), dueFilter.homework.map { it.assignmentId })

        assertEquals("VALIDATION_FAILED", send(client, "GET", "/api/v1/homework?status=LOST", teacher).code())

        assertEquals(HomeworkCountsResponse(toReview = 1, overdue = 1, openTotal = 2),
            send(client, "GET", "/api/v1/homework/counts", teacher).body<HomeworkCountsResponse>())
        assertEquals(HomeworkCountsResponse(open = 2, dueSoon = 2, returned = 0),
            send(client, "GET", "/api/v1/homework/counts", student).body<HomeworkCountsResponse>())
    }

    @Test
    fun `homework can be listed by source document`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val withDocument = assignDocument(client, teacher)
        HomeworkFixtures.assignTextTask(client, teacher, STUDENT_ID)
        val documentId = withDocument.items.first().documentId

        val forTeacher = send(client, "GET", "/api/v1/homework?documentId=$documentId", teacher).body<HomeworkPageResponse>()
        assertEquals(listOf(withDocument.id), forTeacher.homework.map { it.assignmentId })
        val forStudent = send(client, "GET", "/api/v1/homework?documentId=$documentId", getStudentToken(client)).body<HomeworkPageResponse>()
        assertEquals(listOf(withDocument.id), forStudent.homework.map { it.assignmentId })
        val forOther = send(client, "GET", "/api/v1/homework?documentId=$documentId", getStudent2Token(client)).body<HomeworkPageResponse>()
        assertTrue(forOther.homework.isEmpty())
    }

    @Test
    fun `teacher removes one student's homework`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val assignment = HomeworkFixtures.assignTextTask(client, teacher, STUDENT_ID)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        assertEquals(HttpStatusCode.Forbidden, send(client, "DELETE", "/api/v1/homework/$id", getStudentToken(client)).status)
        assertEquals(HttpStatusCode.NoContent, send(client, "DELETE", "/api/v1/homework/$id", teacher).status)
        assertEquals(HttpStatusCode.NotFound, send(client, "GET", "/api/v1/homework/$id", teacher).status)
        assertEquals(HttpStatusCode.OK, send(client, "GET", "/api/v1/assignments/${assignment.id}", getAdminToken(client)).status)
    }
}

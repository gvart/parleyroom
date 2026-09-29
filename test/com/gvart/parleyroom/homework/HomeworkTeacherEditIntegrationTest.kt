package com.gvart.parleyroom.homework

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_1
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_2
import com.gvart.parleyroom.homework.HomeworkFixtures.GAP_BLOCK
import com.gvart.parleyroom.homework.HomeworkFixtures.MC_1
import com.gvart.parleyroom.homework.HomeworkFixtures.MC_BLOCK
import com.gvart.parleyroom.homework.HomeworkFixtures.OPT_BIN
import com.gvart.parleyroom.homework.HomeworkFixtures.Q_OPEN
import com.gvart.parleyroom.homework.HomeworkFixtures.Q_TF
import com.gvart.parleyroom.homework.HomeworkFixtures.READING_BLOCK
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkOutcome
import com.gvart.parleyroom.homework.data.HomeworkResponseType
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.transfer.AssignmentResponse
import com.gvart.parleyroom.homework.transfer.HomeworkResponse
import com.gvart.parleyroom.homework.transfer.UnitResponse
import com.gvart.parleyroom.library.LibraryFixtures
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The teacher follows, edits and grades homework the student has not submitted yet. */
class HomeworkTeacherEditIntegrationTest : IntegrationTest() {

    private suspend fun send(client: HttpClient, method: String, path: String, token: String, body: String? = null): HttpResponse {
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

    private suspend fun assignDocument(client: HttpClient, teacher: String): AssignmentResponse {
        val documentId = HomeworkFixtures.createExerciseDocument(client, teacher)
        val response = HomeworkFixtures.postAssignment(client, teacher,
            """{ "title": "Hausaufgabe Haushalt", "studentIds": ["$STUDENT_ID"],
                 "items": [{ "kind": "DOCUMENT", "documentId": "$documentId" },
                           { "kind": "TASK", "title": "Sprich über deine Wohnung", "responseType": "AUDIO" }] }""")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.body()
    }

    private fun docUnit(item: String, block: String, ref: String, answer: String) =
        """{ "assignmentItemId": "$item", "blockId": "$block", "itemId": "$ref", "answer": $answer }"""

    private suspend fun homework(client: HttpClient, id: String, token: String): HomeworkResponse =
        send(client, "GET", "/api/v1/homework/$id", token).also { assertEquals(HttpStatusCode.OK, it.status) }.body()

    private fun HomeworkResponse.unit(ref: String): UnitResponse = units.single { it.itemId == ref }

    /** The exercise document minus the MC and error-correction blocks; GAP_1 now has a single gap. */
    private val editedBlocks = """
        [
          { "id": "$GAP_BLOCK", "type": "gap_fill", "interactive": true, "instructions": "Ergänze.",
            "items": [
              { "id": "$GAP_1", "text": "Darum ___ du dich kümmern.", "solution": { "answers": [["musst"]] } },
              { "id": "$GAP_2", "text": "Er ___ nach Hause.", "solution": { "answers": [["geht", "läuft"]] } }
            ] },
          { "id": "$READING_BLOCK", "type": "reading", "interactive": true, "title": "Im Haushalt",
            "text": { "type": "doc", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "Peter macht den Abwasch." }] }] },
            "questions": [
              { "id": "$Q_TF", "kind": "TRUE_FALSE", "question": "Peter spült.", "solution": { "isTrue": true } },
              { "id": "$Q_OPEN", "kind": "OPEN", "question": "Was macht Peter?", "solution": { "sampleAnswer": "Den Abwasch." } }
            ] }
        ]
    """.trimIndent()

    @Test
    fun `teacher sees open answers and edits items while every homework is open`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assignDocument(client, teacher)
        val (docItem, audioItem) = assignment.items.map { it.id }
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        val base = "/api/v1/assignments/${assignment.id}/items"

        send(client, "PUT", "/api/v1/homework/$id/answers", student, """{ "answers": [
            ${docUnit(docItem, GAP_BLOCK, GAP_1, """{"gaps":["musst","Wäsche"]}""")},
            ${docUnit(docItem, GAP_BLOCK, GAP_2, """{"gaps":["geht"]}""")},
            ${docUnit(docItem, MC_BLOCK, MC_1, """{"optionIds":["$OPT_BIN"]}""")},
            ${docUnit(docItem, READING_BLOCK, Q_TF, """{"isTrue":true}""")}
        ] }""").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

        // The teacher reads the work in progress.
        val inProgress = homework(client, id, teacher)
        assertEquals(HomeworkStatus.OPEN, inProgress.status)
        assertEquals("""{"gaps":["geht"]}""", inProgress.unit(GAP_2).answer.toString())
        assertEquals(7, inProgress.totalUnits)

        // Edited snapshot: the removed MC unit and the no-longer-fitting GAP_1 answer go, the rest stays.
        val edited = send(client, "PATCH", "$base/$docItem", teacher, """{ "title": "Haushalt (kurz)", "blocks": $editedBlocks }""")
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        assertEquals("Haushalt (kurz)", edited.body<AssignmentResponse>().items.first().title)
        val afterEdit = homework(client, id, student)
        assertEquals(5, afterEdit.totalUnits)
        assertTrue(afterEdit.units.none { it.itemId == MC_1 })
        assertNull(afterEdit.unit(GAP_1).answer)
        assertEquals("""{"gaps":["geht"]}""", afterEdit.unit(GAP_2).answer.toString())
        assertEquals("""{"isTrue":true}""", afterEdit.unit(Q_TF).answer.toString())
        assertEquals(2, afterEdit.answeredUnits)

        // Task text and response type.
        val task = send(client, "PATCH", "$base/$audioItem", teacher, """{ "task": "Drei Sätze", "responseType": "TEXT" }""")
        assertEquals(HttpStatusCode.OK, task.status, task.bodyAsText())
        val taskItem = task.body<AssignmentResponse>().items.single { it.id == audioItem }
        assertEquals("Drei Sätze", taskItem.task)
        assertEquals(HomeworkResponseType.TEXT, taskItem.responseType)

        // Add and remove.
        val added = send(client, "POST", base, teacher, """{ "kind": "TASK", "title": "Foto vom Zimmer", "responseType": "FILE" }""")
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        val items = added.body<AssignmentResponse>().items
        assertEquals(listOf(0, 1, 2), items.map { it.position })
        assertEquals(AssignmentItemKind.TASK, items.last().kind)
        assertEquals(6, homework(client, id, teacher).totalUnits)
        val removed = send(client, "DELETE", "$base/$audioItem", teacher).body<AssignmentResponse>()
        assertEquals(listOf("Haushalt (kurz)", "Foto vom Zimmer"), removed.items.map { it.title })
        assertEquals(listOf(0, 1), removed.items.map { it.position })
        assertEquals(2, homework(client, id, teacher).itemCount)

        // Invalid edits.
        assertEquals("HOMEWORK_ITEM_INVALID", send(client, "PATCH", "$base/$docItem", teacher, """{ "responseType": "TEXT" }""").code())
        assertEquals("HOMEWORK_ITEM_INVALID", send(client, "PATCH", "$base/$docItem", teacher,
            """{ "blocks": [{ "id": "30000000-0000-0000-0000-000000000001", "type": "heading", "interactive": false, "text": "Nur Text", "level": 1 }] }""").code())
        send(client, "DELETE", "$base/${removed.items.last().id}", teacher).also { assertEquals(HttpStatusCode.OK, it.status) }
        val last = send(client, "DELETE", "$base/$docItem", teacher)
        assertEquals(HttpStatusCode.Conflict, last.status)
        assertEquals("ASSIGNMENT_LAST_ITEM", last.code())

        // Once a student handed in, items are frozen.
        assertEquals(HttpStatusCode.OK, send(client, "POST", "/api/v1/homework/$id/submit", student).status)
        val locked = send(client, "PATCH", "$base/$docItem", teacher, """{ "title": "Zu spät" }""")
        assertEquals(HttpStatusCode.Conflict, locked.status)
        assertEquals("ASSIGNMENT_ITEMS_LOCKED", locked.code())
    }

    @Test
    fun `teacher reviews an open homework and marks it done`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = assignDocument(client, teacher)
        val docItem = assignment.items.first().id
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        send(client, "PUT", "/api/v1/homework/$id/answers", student, """{ "answers": [
            ${docUnit(docItem, GAP_BLOCK, GAP_2, """{"gaps":["geht"]}""")},
            ${docUnit(docItem, READING_BLOCK, Q_OPEN, """{"text":"Er spült."}""")}
        ] }""")

        // Draft review on OPEN: saved, still hidden from the student.
        val draft = send(client, "PUT", "/api/v1/homework/$id/review", teacher, """{ "feedback": "Weiter so", "units": [
            { "assignmentItemId": "$docItem", "blockId": "$READING_BLOCK", "itemId": "$Q_OPEN", "correct": true, "comment": "Genau" } ] }""")
        assertEquals(HttpStatusCode.OK, draft.status, draft.bodyAsText())
        assertEquals("Genau", draft.body<HomeworkResponse>().unit(Q_OPEN).comment)
        val hidden = homework(client, id, student)
        assertNull(hidden.feedback)
        assertNull(hidden.unit(Q_OPEN).comment)

        val done = send(client, "POST", "/api/v1/homework/$id/review", teacher, """{ "feedback": "Weiter so", "outcome": "DONE", "units": [
            { "assignmentItemId": "$docItem", "blockId": "$READING_BLOCK", "itemId": "$Q_OPEN", "correct": true, "comment": "Genau" } ] }""")
        assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())

        // Graded like a submission: auto results, score, verdicts and feedback reach the student.
        val result = homework(client, id, student)
        assertEquals(HomeworkStatus.DONE, result.status)
        assertEquals(1, result.attempt)
        assertNotNull(result.doneAt)
        assertEquals(AutoResult.CORRECT, result.unit(GAP_2).autoResult)
        assertEquals(AutoResult.INCORRECT, result.unit(GAP_1).autoResult)
        assertEquals(true, result.unit(Q_OPEN).correct)
        assertEquals("Genau", result.unit(Q_OPEN).comment)
        assertEquals("Weiter so", result.feedback)
        val summary = assertNotNull(result.summary)
        assertEquals(1, summary.closedCorrect)
        assertEquals(5, summary.unanswered)

        // Student-side lock is intact.
        val edit = send(client, "PUT", "/api/v1/homework/$id/answers", student, """{ "answers": [] }""")
        assertEquals("SUBMISSION_LOCKED", edit.code())
        assertEquals(
            listOf(NotificationType.HOMEWORK_REVIEWED, NotificationType.HOMEWORK_ASSIGNED),
            send(client, "GET", "/api/v1/notifications", student).body<NotificationPageResponse>().notifications.map { it.type },
        )
    }

    @Test
    fun `returning an open homework keeps it open with the feedback visible`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = HomeworkFixtures.assignTextTask(client, teacher, STUDENT_ID)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)

        val returned = send(client, "POST", "/api/v1/homework/$id/review", teacher, """{ "feedback": "Mehr Details bitte", "outcome": "RETURNED" }""")
        assertEquals(HttpStatusCode.OK, returned.status, returned.bodyAsText())
        val view = homework(client, id, student)
        assertEquals(HomeworkStatus.OPEN, view.status)
        assertEquals(HomeworkOutcome.RETURNED, view.lastOutcome)
        assertEquals(0, view.attempt)
        assertEquals("Mehr Details bitte", view.feedback)

        val reviewed = send(client, "POST", "/api/v1/homework/$id/review", teacher, """{ "feedback": "Gut", "outcome": "REVIEWED" }""")
        assertEquals(HomeworkStatus.REVIEWED, reviewed.body<HomeworkResponse>().status)
    }

    @Test
    fun `only the owning teacher sees, edits and grades`() = testApp {
        val client = createJsonClient(this)
        val teacher = getTeacherToken(client)
        val student = getStudentToken(client)
        val assignment = HomeworkFixtures.assignTextTask(client, teacher, STUDENT_ID)
        val id = HomeworkFixtures.homeworkIdOf(assignment, STUDENT_ID)
        val item = assignment.items.single().id
        val base = "/api/v1/assignments/${assignment.id}/items"
        LibraryFixtures.otherTeacher()
        val other = getToken(client, "teacher2@test.com")

        assertEquals(HttpStatusCode.NotFound, send(client, "GET", "/api/v1/homework/$id", other).status)
        assertEquals(HttpStatusCode.NotFound, send(client, "PUT", "/api/v1/homework/$id/review", other, """{ "feedback": "x" }""").status)
        assertEquals(HttpStatusCode.NotFound, send(client, "POST", "/api/v1/homework/$id/review", other, """{ "outcome": "DONE" }""").status)
        assertEquals(HttpStatusCode.NotFound, send(client, "PATCH", "$base/$item", other, """{ "title": "x" }""").status)
        assertEquals(HttpStatusCode.NotFound, send(client, "POST", base, other, """{ "kind": "TASK", "title": "x", "responseType": "TEXT" }""").status)
        assertEquals(HttpStatusCode.NotFound, send(client, "DELETE", "$base/$item", other).status)

        assertEquals(HttpStatusCode.Forbidden, send(client, "PATCH", "$base/$item", student, """{ "title": "x" }""").status)
        assertEquals(HttpStatusCode.Forbidden, send(client, "PUT", "/api/v1/homework/$id/review", student, """{ "feedback": "x" }""").status)
        assertEquals(HttpStatusCode.Forbidden, send(client, "POST", "/api/v1/homework/$id/review", student, """{ "outcome": "DONE" }""").status)
        assertEquals(HomeworkStatus.OPEN, homework(client, id, teacher).status)
    }
}

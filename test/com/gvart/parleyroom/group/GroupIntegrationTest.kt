package com.gvart.parleyroom.group

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.group.data.GroupType
import com.gvart.parleyroom.group.transfer.GroupMembersRequest
import com.gvart.parleyroom.group.transfer.GroupRequest
import com.gvart.parleyroom.group.transfer.GroupResponse
import com.gvart.parleyroom.lesson.transfer.CreateLessonRequest
import com.gvart.parleyroom.lesson.transfer.LessonResponse
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
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupIntegrationTest : IntegrationTest() {

    private suspend fun createGroup(
        client: HttpClient,
        token: String,
        studentIds: List<String> = listOf(STUDENT_ID),
    ): HttpResponse = client.post("/api/v1/groups") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(GroupRequest(name = "Sprechclub B1", level = LanguageLevel.B1, type = GroupType.SPEECH, studentIds = studentIds))
    }

    @Test
    fun `teacher creates a group with members and lists it`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createGroup(client, token)
        assertEquals(HttpStatusCode.Created, response.status)
        val group = response.body<GroupResponse>()
        assertEquals(GroupType.SPEECH, group.type)
        assertEquals(listOf(STUDENT_ID), group.members.map { it.id })

        val list = client.get("/api/v1/groups") { bearerAuth(token) }.body<List<GroupResponse>>()
        assertEquals(listOf(group.id), list.map { it.id })
    }

    @Test
    fun `members must be the teacher's students`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = createGroup(client, token, studentIds = listOf(STUDENT_2_ID))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("STUDENT_NOT_LINKED", response.body<ProblemDetail>().code)
    }

    @Test
    fun `teacher manages group members`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val groupId = createGroup(client, token, studentIds = emptyList()).body<GroupResponse>().id

        val added = client.post("/api/v1/groups/$groupId/members") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GroupMembersRequest(listOf(STUDENT_ID)))
        }.body<GroupResponse>()
        assertEquals(1, added.members.size)

        // Adding again is idempotent
        val again = client.post("/api/v1/groups/$groupId/members") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GroupMembersRequest(listOf(STUDENT_ID)))
        }.body<GroupResponse>()
        assertEquals(1, again.members.size)

        val removed = client.delete("/api/v1/groups/$groupId/members/$STUDENT_ID") { bearerAuth(token) }.body<GroupResponse>()
        assertEquals(0, removed.members.size)

        val replaced = client.put("/api/v1/groups/$groupId/members") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GroupMembersRequest(listOf(STUDENT_ID)))
        }.body<GroupResponse>()
        assertEquals(listOf(STUDENT_ID), replaced.members.map { it.id })
    }

    @Test
    fun `teacher updates and deletes a group`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val groupId = createGroup(client, token).body<GroupResponse>().id

        val updated = client.put("/api/v1/groups/$groupId") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(GroupRequest(name = "Leseclub", type = GroupType.READING))
        }.body<GroupResponse>()
        assertEquals("Leseclub", updated.name)
        assertEquals(GroupType.READING, updated.type)
        assertNull(updated.level)
        assertEquals(1, updated.members.size)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/groups/$groupId") { bearerAuth(token) }.status)
        val missing = client.get("/api/v1/groups/$groupId") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("GROUP_NOT_FOUND", missing.body<ProblemDetail>().code)
    }

    @Test
    fun `students cannot manage groups`() = testApp {
        val client = createJsonClient(this)
        val studentToken = getStudentToken(client)

        assertEquals(HttpStatusCode.Forbidden, createGroup(client, studentToken, emptyList()).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/groups") { bearerAuth(studentToken) }.status)
    }

    @Test
    fun `club lesson can be linked to a group`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val groupId = createGroup(client, token).body<GroupResponse>().id

        val lesson = client.post("/api/v1/lessons") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(
                CreateLessonRequest(
                    teacherId = TEACHER_ID,
                    studentIds = emptyList(),
                    title = "Sprechclub",
                    type = LessonType.SPEAKING_CLUB,
                    scheduledAt = OffsetDateTime.now().plusDays(3),
                    topic = "Reisen",
                    maxParticipants = 6,
                    groupId = groupId,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, lesson.status)
        val lessonId = lesson.body<LessonResponse>().id
        assertEquals(groupId, lesson.body<LessonResponse>().groupId)

        // Deleting the group keeps the lesson but clears the link
        client.delete("/api/v1/groups/$groupId") { bearerAuth(token) }
        val after = client.get("/api/v1/lessons/$lessonId") { bearerAuth(token) }.body<LessonResponse>()
        assertNull(after.groupId)
    }
}

package com.gvart.parleyroom.notification

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.MarkAllViewedResponse
import com.gvart.parleyroom.notification.transfer.MarkViewedRequest
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationUnreadIntegrationTest : IntegrationTest() {

    private fun notify(userId: String, viewed: Boolean = false, deliverAfter: OffsetDateTime? = null): UUID = transaction {
        NotificationTable.insertAndGetId {
            it[NotificationTable.userId] = UUID.fromString(userId)
            it[actorId] = UUID.fromString(TEACHER_ID)
            it[type] = NotificationType.LESSON_CREATED
            it[NotificationTable.viewed] = viewed
            it[NotificationTable.deliverAfter] = deliverAfter
        }.value
    }

    private fun isViewed(id: UUID): Boolean = transaction {
        NotificationTable.selectAll().where { NotificationTable.id eq id }.single()[NotificationTable.viewed]
    }

    private suspend fun page(client: HttpClient, token: String, query: String = ""): NotificationPageResponse =
        client.get("/api/v1/notifications$query") { bearerAuth(token) }.also { assertEquals(HttpStatusCode.OK, it.status) }.body()

    private suspend fun viewedAll(client: HttpClient, token: String): MarkAllViewedResponse =
        client.post("/api/v1/notifications/viewed-all") { bearerAuth(token) }.also { assertEquals(HttpStatusCode.OK, it.status) }.body()

    @Test
    fun `unread filter returns only unread and counts them`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val unread = List(3) { notify(STUDENT_ID) }
        repeat(2) { notify(STUDENT_ID, viewed = true) }
        notify(STUDENT_2_ID)

        val all = page(client, token)
        assertEquals(5, all.total)
        assertEquals(3, all.unreadCount)

        val filtered = page(client, token, "?unread=true&pageSize=2")
        assertEquals(3, filtered.total)
        assertEquals(3, filtered.unreadCount)
        assertEquals(2, filtered.notifications.size)
        assertTrue(filtered.notifications.none { it.viewed })
        val second = page(client, token, "?unread=true&pageSize=2&page=2")
        assertEquals(unread.map(UUID::toString).toSet(), (filtered.notifications + second.notifications).map { it.id }.toSet())

        // unread=false is the full list.
        assertEquals(5, page(client, token, "?unread=false").total)
    }

    @Test
    fun `undelivered notifications are neither listed nor counted`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client) // starts the app, which reloads the test data
        notify(STUDENT_ID)
        val later = notify(STUDENT_ID, deliverAfter = OffsetDateTime.now().plusHours(1))

        val filtered = page(client, token, "?unread=true")
        assertEquals(1, filtered.total)
        assertEquals(1, filtered.unreadCount)
        assertTrue(filtered.notifications.none { it.id == later.toString() })
    }

    @Test
    fun `an invalid unread value is a field error`() = testApp {
        val client = createJsonClient(this)
        val response = client.get("/api/v1/notifications?unread=yes") { bearerAuth(getStudentToken(client)) }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `viewed-all marks only the caller's delivered notifications and is idempotent`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val mine = List(3) { notify(STUDENT_ID) }
        notify(STUDENT_ID, viewed = true)
        val later = notify(STUDENT_ID, deliverAfter = OffsetDateTime.now().plusHours(1))
        val others = notify(STUDENT_2_ID)

        assertEquals(3, viewedAll(client, token).updated)
        assertTrue(mine.all(::isViewed))
        assertEquals(false, isViewed(others))
        assertEquals(false, isViewed(later))
        assertEquals(0, page(client, token).unreadCount)
        assertEquals(0, page(client, token, "?unread=true").total)

        assertEquals(0, viewedAll(client, token).updated)
        assertEquals(1, page(client, getStudent2Token(client)).unreadCount)
    }

    @Test
    fun `viewed-all needs a token`() = testApp {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/notifications/viewed-all").status)
    }

    @Test
    fun `marking one viewed lowers the unread count`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val first = notify(STUDENT_ID)
        notify(STUDENT_ID)

        client.post("/api/v1/notifications/viewed") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(MarkViewedRequest(notificationIds = listOf(first.toString())))
        }
        assertEquals(1, page(client, token).unreadCount)
    }

    @Test
    fun `actor carries display info and avatarUrl`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        transaction { exec("UPDATE users SET avatar_url = 'avatars/$TEACHER_ID/abc.png' WHERE id = '$TEACHER_ID'") }
        notify(STUDENT_ID)

        val actor = page(client, token).notifications.single().actor
        assertEquals(TEACHER_ID, actor.id)
        assertEquals("Test", actor.firstName)
        assertEquals("Teacher", actor.lastName)
        assertEquals("/api/v1/users/$TEACHER_ID/avatar?v=abc.png", actor.avatarUrl)
    }
}

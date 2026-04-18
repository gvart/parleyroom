package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.TelegramLinkResult
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TelegramLinkWidgetIntegrationTest : IntegrationTest() {

    private val telegramIdForAdmin: Long = 200_001L
    private val telegramIdForTeacher: Long = 200_002L

    @Test
    fun `link via widget persists telegram_id and username on the authenticated user`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val payload = TelegramWidgetPayload.build(
            botToken = TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForAdmin,
            firstName = "Admin",
            username = "admin_tg",
        )

        val response = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val link = response.body<TelegramLinkResult>()
        assertEquals(telegramIdForAdmin, link.telegramId)
        assertEquals("admin_tg", link.telegramUsername)

        val row = transaction {
            UserTable.selectAll().where { UserTable.id eq UUID.fromString(ADMIN_ID) }.single()
        }
        assertEquals(telegramIdForAdmin, row[UserTable.telegramId])
        assertEquals("admin_tg", row[UserTable.telegramUsername])

        val me = client.get("/api/v1/users/me") { bearerAuth(adminToken) }.body<UserResponse>()
        assertEquals(telegramIdForAdmin, me.telegramId)
        assertEquals("admin_tg", me.telegramUsername)
    }

    @Test
    fun `link via widget succeeds when username is absent`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val payload = TelegramWidgetPayload.build(
            botToken = TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForAdmin,
            firstName = "Admin",
            username = null,
        )

        val response = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.body<TelegramLinkResult>().telegramUsername)
    }

    @Test
    fun `link via widget with tampered hash returns 401`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val payload = TelegramWidgetPayload.build(
            botToken = TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForAdmin,
            overrideHash = "0".repeat(64),
        )

        val response = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `link via widget signed with wrong bot token returns 401`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val payload = TelegramWidgetPayload.build(
            botToken = "some-other-bot-token",
            userId = telegramIdForAdmin,
        )

        val response = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `link via widget with stale auth_date returns 401`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val twoDaysAgo = System.currentTimeMillis() / 1000 - 60L * 60 * 48
        val payload = TelegramWidgetPayload.build(
            botToken = TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForAdmin,
            authDateEpochSeconds = twoDaysAgo,
        )

        val response = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `link via widget when telegram id already owned by another user returns 409`() = testApp {
        val client = createJsonClient(this)

        val teacherToken = getTeacherToken(client)
        val teacherPayload = TelegramWidgetPayload.build(
            botToken = TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForTeacher,
        )
        val teacherLink = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(teacherPayload)
        }
        assertEquals(HttpStatusCode.OK, teacherLink.status)

        val adminToken = getAdminToken(client)
        val adminPayload = TelegramWidgetPayload.build(
            botToken = TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForTeacher,
        )
        val adminLink = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(adminPayload)
        }
        assertEquals(HttpStatusCode.Conflict, adminLink.status)
    }

    @Test
    fun `link via widget without authentication returns 401`() = testApp {
        val client = createJsonClient(this)
        val payload = TelegramWidgetPayload.build(
            botToken = TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForAdmin,
        )
        val response = client.post("/api/v1/users/me/telegram/link-widget") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `relinking same telegram id via widget to same user is idempotent`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        repeat(2) {
            val payload = TelegramWidgetPayload.build(
                botToken = TEST_TELEGRAM_BOT_TOKEN,
                userId = telegramIdForAdmin,
            )
            val resp = client.post("/api/v1/users/me/telegram/link-widget") {
                contentType(ContentType.Application.Json)
                bearerAuth(adminToken)
                setBody(payload)
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertNotNull(resp.body<TelegramLinkResult>().telegramId)
        }
    }
}

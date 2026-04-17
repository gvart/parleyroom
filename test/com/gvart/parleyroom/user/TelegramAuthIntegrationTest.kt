package com.gvart.parleyroom.user

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.TelegramAuthRequest
import com.gvart.parleyroom.user.transfer.TelegramLinkResult
import com.gvart.parleyroom.user.transfer.UserResponse
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelegramAuthIntegrationTest : IntegrationTest() {

    private val telegramIdForAdmin: Long = 100_001L
    private val telegramIdForTeacher: Long = 100_002L

    @Test
    fun `sign-in with valid initData but no linked user returns 404 telegram_not_linked`() = testApp {
        val client = createJsonClient(this)
        val initData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = 999_000L)

        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(initData))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `link then sign-in returns JWT for linked user`() = testApp {
        val client = createJsonClient(this)
        val initData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin, username = "admin_tg")

        // 1. Authenticated link as admin
        val adminToken = getAdminToken(client)
        val linkResp = client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(TelegramAuthRequest(initData))
        }
        assertEquals(HttpStatusCode.OK, linkResp.status)
        val link = linkResp.body<TelegramLinkResult>()
        assertEquals(telegramIdForAdmin, link.telegramId)
        assertEquals("admin_tg", link.telegramUsername)

        // 2. Mini-app sign-in now works (new initData so hash is fresh too)
        val fresh = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin, username = "admin_tg")
        val signIn = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(fresh))
        }
        assertEquals(HttpStatusCode.OK, signIn.status)
        val tokens = signIn.body<AuthenticateResponse>()
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())

        // 3. The JWT belongs to the admin user
        val me = client.get("/api/v1/users/me") { bearerAuth(tokens.accessToken) }.body<UserResponse>()
        assertEquals(ADMIN_ID, me.id)
        assertEquals(telegramIdForAdmin, me.telegramId)
        assertEquals("admin_tg", me.telegramUsername)
    }

    @Test
    fun `relinking the same telegram_id to the same user is idempotent`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val tgId = telegramIdForAdmin

        repeat(2) {
            val initData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = tgId)
            val resp = client.post("/api/v1/users/me/telegram/link") {
                contentType(ContentType.Application.Json)
                bearerAuth(adminToken)
                setBody(TelegramAuthRequest(initData))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
        }
    }

    @Test
    fun `linking a telegram_id already owned by another user returns 409`() = testApp {
        val client = createJsonClient(this)

        // Teacher links telegramIdForAdmin
        val teacherToken = getTeacherToken(client)
        val teacherInit = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin)
        val teacherLink = client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(TelegramAuthRequest(teacherInit))
        }
        assertEquals(HttpStatusCode.OK, teacherLink.status)

        // Admin now tries to link the same id — conflict
        val adminToken = getAdminToken(client)
        val adminInit = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin)
        val adminLink = client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(TelegramAuthRequest(adminInit))
        }
        assertEquals(HttpStatusCode.Conflict, adminLink.status)
    }

    @Test
    fun `sign-in with tampered hash returns 401`() = testApp {
        val client = createJsonClient(this)
        val initData = TelegramInitData.build(
            TEST_TELEGRAM_BOT_TOKEN,
            userId = 42L,
            overrideHash = "0".repeat(64),
        )
        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(initData))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `sign-in with wrong bot token signature returns 401`() = testApp {
        val client = createJsonClient(this)
        val initData = TelegramInitData.build(
            botToken = "some-other-bot-token",
            userId = telegramIdForAdmin,
        )
        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(initData))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `sign-in with auth_date older than max age returns 401`() = testApp {
        val client = createJsonClient(this)
        val twoDaysAgo = System.currentTimeMillis() / 1000 - 60L * 60 * 48
        val initData = TelegramInitData.build(
            TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForAdmin,
            authDateEpochSeconds = twoDaysAgo,
        )
        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(initData))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `sign-in with empty initData returns 400`() = testApp {
        val client = createJsonClient(this)
        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(""))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `link without authentication returns 401`() = testApp {
        val client = createJsonClient(this)
        val initData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin)
        val response = client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(initData))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `unlink clears columns and subsequent sign-in returns 404`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val initData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForTeacher)

        // Link first
        client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(TelegramAuthRequest(initData))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        // Unlink
        val unlink = client.delete("/api/v1/users/me/telegram") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.NoContent, unlink.status)

        // Columns cleared in DB
        val row = transaction {
            UserTable.selectAll().where { UserTable.id eq UUID.fromString(ADMIN_ID) }.single()
        }
        assertNull(row[UserTable.telegramId])
        assertNull(row[UserTable.telegramUsername])

        // Mini-app sign-in for that telegram_id now fails
        val fresh = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForTeacher)
        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(fresh))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `link updates telegram_id column on the authenticated user`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val initData = TelegramInitData.build(
            TEST_TELEGRAM_BOT_TOKEN,
            userId = telegramIdForTeacher,
            username = "teach_tg",
        )

        client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(teacherToken)
            setBody(TelegramAuthRequest(initData))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        val row = transaction {
            UserTable.selectAll().where { UserTable.id eq UUID.fromString(TEACHER_ID) }.single()
        }
        assertEquals(telegramIdForTeacher, row[UserTable.telegramId])
        assertEquals("teach_tg", row[UserTable.telegramUsername])

        // Admin row still has nothing linked
        val adminRow = transaction {
            UserTable.selectAll().where { UserTable.id eq UUID.fromString(ADMIN_ID) }.single()
        }
        assertNull(adminRow[UserTable.telegramId])
    }

    @Test
    fun `sign-in after link rejects initData with wrong user id even if signature is valid`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val linkData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin)
        client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(TelegramAuthRequest(linkData))
        }

        // Valid signature but a different (unlinked) telegram user
        val otherUser = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = 777_777L)
        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(otherUser))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `sign-in with initData missing hash returns 400`() = testApp {
        val client = createJsonClient(this)
        // No way for TelegramInitData helper to omit hash; craft a minimal payload inline.
        val malformed = "auth_date=${System.currentTimeMillis() / 1000}&user=%7B%22id%22%3A1%2C%22first_name%22%3A%22X%22%7D"
        val response = client.post("/api/v1/token/telegram-miniapp") {
            contentType(ContentType.Application.Json)
            setBody(TelegramAuthRequest(malformed))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `profile reflects telegram link state via me endpoint`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)

        val before = client.get("/api/v1/users/me") { bearerAuth(adminToken) }.body<UserResponse>()
        assertNull(before.telegramId)

        val initData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin, username = "me_tg")
        client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(TelegramAuthRequest(initData))
        }

        val after = client.get("/api/v1/users/me") { bearerAuth(adminToken) }.body<UserResponse>()
        assertEquals(telegramIdForAdmin, after.telegramId)
        assertEquals("me_tg", after.telegramUsername)
    }

    @Test
    fun `link is idempotent when re-run from same user after an unlink`() = testApp {
        val client = createJsonClient(this)
        val adminToken = getAdminToken(client)
        val initData = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin)

        client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(TelegramAuthRequest(initData))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }

        client.delete("/api/v1/users/me/telegram") { bearerAuth(adminToken) }
            .also { assertEquals(HttpStatusCode.NoContent, it.status) }

        // Hand-clear in case admin had stale state from another test
        transaction {
            UserTable.update({ UserTable.id eq UUID.fromString(ADMIN_ID) }) {
                it[telegramId] = null
                it[telegramUsername] = null
            }
        }

        val fresh = TelegramInitData.build(TEST_TELEGRAM_BOT_TOKEN, userId = telegramIdForAdmin)
        val relink = client.post("/api/v1/users/me/telegram/link") {
            contentType(ContentType.Application.Json)
            bearerAuth(adminToken)
            setBody(TelegramAuthRequest(fresh))
        }
        assertEquals(HttpStatusCode.OK, relink.status)
        assertNotNull(relink.body<TelegramLinkResult>().telegramId)
    }
}

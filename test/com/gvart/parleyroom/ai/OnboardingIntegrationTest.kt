package com.gvart.parleyroom.ai

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.ai.data.DraftItemKind
import com.gvart.parleyroom.ai.data.DraftStatus
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.ai.transfer.DraftBundleResponse
import com.gvart.parleyroom.ai.transfer.DraftKind
import com.gvart.parleyroom.ai.transfer.GenerateDraftRequest
import com.gvart.parleyroom.ai.transfer.OnboardingResponse
import com.gvart.parleyroom.ai.transfer.SendDraftRequest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.transfer.MaterialResponse
import com.gvart.parleyroom.progress.data.StudentGrammarCoverageTable
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.topic.data.GrammarTopicTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

class OnboardingIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.material(token: String, name: String, bytes: ByteArray = pdf("Heute arbeiten wir im Garten mit der Gießkanne.")): UUID {
        val response = upload(token, name, "$name.pdf", "application/pdf", bytes)
        assertEquals(HttpStatusCode.Created, response.status, response.body<String>())
        return UUID.fromString(response.body<MaterialResponse>().id)
    }

    /** Invites a student with [materials] and registers them; returns the new student's id. */
    private suspend fun HttpClient.inviteAndRegister(token: String, materials: List<UUID>, level: LanguageLevel? = null): UUID {
        val email = "${Uuid.random()}@test.com"
        val invite = post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(InviteUserRequest(email = email, role = UserRole.STUDENT, materialIds = materials.map(UUID::toString), level = level))
        }
        assertEquals(HttpStatusCode.Created, invite.status, invite.body<String>())
        val register = post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("token" to invite.body<InviteUserResponse>().token, "firstName" to "New", "lastName" to "Student",
                "email" to email, "password" to TEST_PASSWORD))
        }
        assertEquals(HttpStatusCode.Created, register.status, register.body<String>())
        return transaction { UserTable.selectAll().where { UserTable.email eq email }.single()[UserTable.id].value }
    }

    private suspend fun HttpClient.onboarding(token: String, studentId: UUID): OnboardingResponse {
        val response = get("/api/v1/students/$studentId/onboarding") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.body<String>())
        return response.body()
    }

    /** Polls until the onboarding draft exists and its job finished. */
    private suspend fun HttpClient.awaitOnboarding(token: String, studentId: UUID): OnboardingResponse {
        repeat(300) {
            val status = onboarding(token, studentId)
            val job = status.bundle?.job?.status
            if (job == GenerationJobStatus.SUCCEEDED || job == GenerationJobStatus.FAILED) return status
            delay(50)
        }
        fail("The onboarding draft of $studentId did not finish")
    }

    private suspend fun HttpClient.retry(token: String, studentId: UUID): HttpResponse =
        post("/api/v1/students/$studentId/onboarding/draft") { bearerAuth(token) }

    private suspend fun HttpClient.bundle(token: String, id: String): DraftBundleResponse =
        get("/api/v1/ai/draft-bundles/$id") { bearerAuth(token) }.body()

    private suspend fun HttpClient.approveAndSend(token: String, bundleId: String) {
        post("/api/v1/ai/draft-bundles/$bundleId/approve-all") { bearerAuth(token) }
        val sent = post("/api/v1/ai/draft-bundles/$bundleId/send") {
            contentType(ContentType.Application.Json); bearerAuth(token); setBody(SendDraftRequest())
        }
        assertEquals(HttpStatusCode.OK, sent.status, sent.body<String>())
    }

    private suspend fun code(response: HttpResponse) = response.status to response.body<ProblemDetail>().code

    private fun sharedWith(studentId: UUID): Set<UUID> = transaction {
        MaterialShareTable.selectAll().where { MaterialShareTable.studentId eq studentId }.map { it[MaterialShareTable.materialId].value }.toSet()
    }

    @Test
    fun `signing up with two PDFs starts an onboarding words draft`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val a = client.material(token, "Lektion-1")
        val b = client.material(token, "Lektion-2", pdf("Der Schuppen steht neben dem Balkon."))

        val student = client.inviteAndRegister(token, listOf(a, b))

        val status = client.awaitOnboarding(token, student)
        assertTrue(status.aiAvailable)
        assertEquals(setOf(a.toString(), b.toString()), status.materials.map { it.id }.toSet())
        assertTrue(status.materials.all { it.aiReadable })
        val summary = status.bundle!!
        assertTrue(summary.onboarding)
        assertEquals(GenerationJobStatus.SUCCEEDED, summary.job?.status, summary.job?.error?.toString())
        assertEquals(DraftStatus.DRAFT, summary.status)
        assertTrue(summary.itemCount > 0)
        val bundle = client.bundle(token, summary.id)
        assertTrue(bundle.onboarding)
        assertEquals(listOf(DraftKind.WORDS), bundle.input.kinds)
        assertEquals(Prompts.ONBOARDING_INSTRUCTIONS, bundle.input.prompt)
        assertEquals(setOf(a.toString(), b.toString()), bundle.materials.map { it.id }.toSet())
        assertTrue(bundle.items.all { it.kind == DraftItemKind.WORD && !it.approved })
        assertEquals(listOf(student.toString()), bundle.recipients.map { it.id })
    }

    @Test
    fun `without AI the signup still works and shares the materials`() =
        testApp(mapOf("ai.provider" to "anthropic", "ai.anthropic_api_key" to "")) {
            val client = createJsonClient(this)
            val token = getTeacherToken(client)
            val a = client.material(token, "Lektion")

            val student = client.inviteAndRegister(token, listOf(a))

            assertEquals(setOf(a), sharedWith(student))
            val status = client.onboarding(token, student)
            assertFalse(status.aiAvailable)
            assertNull(status.bundle)
            assertEquals(listOf(a.toString()), status.materials.map { it.id })
            assertEquals(HttpStatusCode.ServiceUnavailable to "AI_NOT_CONFIGURED", code(client.retry(token, student)))
        }

    @Test
    fun `a teacher at the job limit gets no draft on signup and can start it later`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val a = client.material(token, "Lektion")
        val busy = transaction {
            List(2) {
                GenerationJobTable.insertAndGetId {
                    it[teacherId] = TEACHER
                    it[kind] = GenerationJobKind.GENERATE
                    it[status] = GenerationJobStatus.RUNNING
                    it[input] = buildJsonObject { }
                    it[model] = "fake"
                    it[createdAt] = OffsetDateTime.now()
                }.value
            }
        }

        val student = client.inviteAndRegister(token, listOf(a))
        delay(500) // the background start fails on the limit
        assertNull(client.onboarding(token, student).bundle)
        assertEquals(HttpStatusCode.TooManyRequests to "AI_RATE_LIMITED", code(client.retry(token, student)))

        transaction { busy.forEach { id -> GenerationJobTable.deleteWhere { GenerationJobTable.id eq id } } }
        val retried = client.retry(token, student)
        assertEquals(HttpStatusCode.Accepted, retried.status, retried.body<String>())
        assertTrue(retried.body<DraftBundleResponse>().onboarding)
        val status = client.awaitOnboarding(token, student)
        assertEquals(GenerationJobStatus.SUCCEEDED, status.bundle?.job?.status)
    }

    @Test
    fun `materials without text are skipped, and none readable is AI_MATERIAL_NO_TEXT`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val empty = client.material(token, "Scan", pdf(""))
        val readable = client.material(token, "Lektion")
        val image = UUID.fromString(client.upload(token, "Bild", "bild.png", "image/png", byteArrayOf(1, 2, 3)).body<MaterialResponse>().id)

        val student = client.inviteAndRegister(token, listOf(empty, readable, image))
        val status = client.awaitOnboarding(token, student)
        assertEquals(mapOf(empty.toString() to true, readable.toString() to true, image.toString() to false),
            status.materials.associate { it.id to it.aiReadable })
        val bundle = client.bundle(token, status.bundle!!.id)
        assertEquals(GenerationJobStatus.SUCCEEDED, bundle.job?.status)
        assertEquals(listOf(readable.toString()), bundle.materials.map { it.id })
        assertEquals(listOf(readable.toString()), bundle.input.materialIds)

        val unreadable = client.inviteAndRegister(token, listOf(client.material(token, "Scan-2", pdf("")), image))
        delay(300)
        assertNull(client.onboarding(token, unreadable).bundle)
        assertEquals(HttpStatusCode.BadRequest to "AI_MATERIAL_NO_TEXT", code(client.retry(token, unreadable)))
        assertNull(client.onboarding(token, unreadable).bundle, "a bundle whose start failed is not kept")
    }

    @Test
    fun `another open draft for the student is AI_DRAFT_OPEN, a normal generate clears the onboarding flag`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = client.inviteAndRegister(token, listOf(client.material(token, "Lektion")))
        val onboarding = client.awaitOnboarding(token, student).bundle!!

        // A normal generate reuses the open onboarding draft and makes it an ordinary one.
        val generated = client.post("/api/v1/students/$student/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(prompt = "Wörter zum Thema Garten", kinds = listOf(DraftKind.WORDS)))
        }
        assertEquals(HttpStatusCode.Accepted, generated.status, generated.body<String>())
        val normal = client.awaitBundle(token, generated.body())
        assertEquals(onboarding.id, normal.id)
        assertFalse(normal.onboarding)

        assertEquals(HttpStatusCode.Conflict to "AI_DRAFT_OPEN", code(client.retry(token, student)))
        assertNull(client.onboarding(token, student).bundle, "no onboarding draft left")

        // Once the teacher's draft is discarded the onboarding draft can start again.
        client.delete("/api/v1/ai/draft-bundles/${normal.id}") { bearerAuth(token) }
        val restarted = client.retry(token, student)
        assertEquals(HttpStatusCode.Accepted, restarted.status, restarted.body<String>())
        assertNotEquals(normal.id, restarted.body<DraftBundleResponse>().id)
        client.awaitOnboarding(token, student)
    }

    @Test
    fun `only a teacher of the student sees and starts the onboarding`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val student = client.inviteAndRegister(token, listOf(client.material(token, "Lektion")))
        client.awaitOnboarding(token, student)

        val otherEmail = "${Uuid.random()}@test.com"
        transaction {
            UserTable.insert {
                it[email] = otherEmail
                it[firstName] = "Other"
                it[lastName] = "Teacher"
                it[passwordHash] = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt())
                it[role] = UserRole.TEACHER
                it[initials] = "OT"
            }
        }
        val other = getToken(client, otherEmail)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/students/$student/onboarding") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.retry(other, student).status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/students/$student/onboarding") { bearerAuth(getStudentToken(client)) }.status)
    }

    @Test
    fun `sending the onboarding draft marks its grammar covered`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        LibraryFixtures.grammar("Perfekt", LanguageLevel.B1, position = 0)
        val token = getTeacherToken(client)
        // The marker makes the fake tag every word with the library's Perfekt and a new "Wortbildung".
        val material = client.material(token, "Lektion", pdf("[fake:suggest-tags] Heute arbeiten wir im Garten mit der Gießkanne."))
        val student = client.inviteAndRegister(token, listOf(material), LanguageLevel.B1)
        val bundle = client.awaitOnboarding(token, student).bundle!!
        assertEquals(GenerationJobStatus.SUCCEEDED, bundle.job?.status, bundle.job?.error?.toString())

        client.approveAndSend(token, bundle.id)

        // A new grammar topic of a student draft gets the student's level.
        assertEquals(LanguageLevel.B1, transaction {
            GrammarTopicTable.selectAll().where { GrammarTopicTable.name eq "Wortbildung" }.single()[GrammarTopicTable.level]
        })
        val progress = client.get("/api/v1/students/$student/progress") { bearerAuth(token) }.body<JsonObject>()
        val items = progress["grammar"]!!.jsonArray.flatMap { it.jsonObject["items"]!!.jsonArray }.map { it.jsonObject }
        listOf("Perfekt", "Wortbildung").forEach { name ->
            val item = items.single { it["name"]!!.jsonPrimitive.content == name }
            assertEquals("COVERED", item["effective"]!!.jsonPrimitive.content, name)
            assertEquals(JsonNull, item["override"])
        }
        assertEquals(2L, transaction {
            StudentGrammarCoverageTable.selectAll().where { StudentGrammarCoverageTable.studentId eq student }.count()
        })
        assertEquals(DraftStatus.SENT, client.onboarding(token, student).bundle?.status)
    }

    @Test
    fun `sending an ordinary student draft writes no coverage`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val material = client.material(token, "Lektion", pdf("[fake:suggest-tags] Heute arbeiten wir im Garten mit der Gießkanne."))
        val generated = client.post("/api/v1/students/$STUDENT/draft-bundles") {
            contentType(ContentType.Application.Json); bearerAuth(token)
            setBody(GenerateDraftRequest(kinds = listOf(DraftKind.WORDS), materialIds = listOf(material.toString())))
        }
        assertEquals(HttpStatusCode.Accepted, generated.status, generated.body<String>())
        val bundle = client.awaitBundle(token, generated.body())
        assertFalse(bundle.onboarding)
        assertTrue(bundle.items.any { it.word!!.grammarTopics.isNotEmpty() })

        client.approveAndSend(token, bundle.id)

        assertEquals(0L, transaction { StudentGrammarCoverageTable.selectAll().count() })
    }
}

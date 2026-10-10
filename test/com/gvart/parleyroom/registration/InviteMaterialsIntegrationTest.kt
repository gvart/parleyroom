package com.gvart.parleyroom.registration

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.material.data.FolderShareTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.material.transfer.FolderTreeNode
import com.gvart.parleyroom.material.transfer.MaterialPageResponse
import com.gvart.parleyroom.registration.data.RegistrationMaterialTable
import com.gvart.parleyroom.registration.data.RegistrationTable
import com.gvart.parleyroom.registration.transfer.InviteUserRequest
import com.gvart.parleyroom.registration.transfer.InviteUserResponse
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class InviteMaterialsIntegrationTest : IntegrationTest() {

    private val teacher = UUID.fromString(TEACHER_ID)

    private fun seedMaterial(owner: UUID = teacher, name: String = "Lektion", type: MaterialType = MaterialType.PDF): UUID =
        transaction {
            MaterialTable.insertAndGetId {
                it[teacherId] = owner
                it[MaterialTable.name] = name
                it[MaterialTable.type] = type
                it[url] = if (type == MaterialType.LINK) "https://example.com" else "materials/$name.pdf"
                it[createdAt] = OffsetDateTime.now()
            }.value
        }

    private fun seedTeacher(): UUID = transaction {
        UserTable.insertAndGetId {
            it[email] = "${Uuid.random()}@test.com"
            it[firstName] = "Other"
            it[lastName] = "Teacher"
            it[passwordHash] = "x"
            it[role] = UserRole.TEACHER
            it[initials] = "OT"
        }.value
    }

    private suspend fun HttpClient.invite(
        token: String,
        email: String,
        materialIds: List<UUID> = emptyList(),
        level: LanguageLevel? = null,
        role: UserRole = UserRole.STUDENT,
    ): HttpResponse = post("/api/v1/registration/invite") {
        contentType(ContentType.Application.Json)
        bearerAuth(token)
        setBody(InviteUserRequest(email = email, role = role, materialIds = materialIds.map(UUID::toString), level = level))
    }

    private suspend fun HttpClient.register(registrationToken: String, email: String): HttpResponse =
        post("/api/v1/registration") {
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "token" to registrationToken,
                    "firstName" to "New",
                    "lastName" to "Student",
                    "email" to email,
                    "password" to TEST_PASSWORD,
                )
            )
        }

    private fun joinedMaterials(email: String): Set<UUID> = transaction {
        val registrationId = RegistrationTable.selectAll()
            .where { RegistrationTable.email eq email }
            .single()[RegistrationTable.id]
        RegistrationMaterialTable.selectAll()
            .where { RegistrationMaterialTable.registrationId eq registrationId }
            .map { it[RegistrationMaterialTable.materialId].value }
            .toSet()
    }

    private fun sharedWith(studentId: UUID): Set<UUID> = transaction {
        MaterialShareTable.selectAll()
            .where { MaterialShareTable.studentId eq studentId }
            .map { it[MaterialShareTable.materialId].value }
            .toSet()
    }

    private fun sharedFoldersWith(studentId: UUID): Set<UUID> = transaction {
        FolderShareTable.selectAll()
            .where { FolderShareTable.studentId eq studentId }
            .map { it[FolderShareTable.folderId].value }
            .toSet()
    }

    private fun userByEmail(email: String) = transaction {
        UserTable.selectAll().where { UserTable.email eq email }.single()
    }

    @Test
    fun `teacher invite with two materials links them and files them into an onboarding folder`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val a = seedMaterial(name = "A")
        val b = seedMaterial(name = "B")

        val response = client.invite(token, email, listOf(a, b), LanguageLevel.B1)

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(setOf(a, b), joinedMaterials(email))
        transaction {
            val folder = MaterialFolderTable.selectAll()
                .where { MaterialFolderTable.name eq "Onboarding – $email" }
                .single()
            assertEquals(teacher, folder[MaterialFolderTable.teacherId].value)
            assertNull(folder[MaterialFolderTable.parentFolderId])
            val folderIds = MaterialTable.selectAll()
                .where { MaterialTable.folderId eq folder[MaterialFolderTable.id] }
                .map { it[MaterialTable.id].value }
                .toSet()
            assertEquals(setOf(a, b), folderIds)
            assertEquals(LanguageLevel.B1, RegistrationTable.selectAll().where { RegistrationTable.email eq email }.single()[RegistrationTable.level])
        }
    }

    @Test
    fun `onboarding folder with the same name is reused`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val a = seedMaterial(name = "A")
        val b = seedMaterial(name = "B")

        assertEquals(HttpStatusCode.Created, client.invite(token, email, listOf(a)).status)
        transaction {
            RegistrationTable.update({ RegistrationTable.email eq email }) {
                it[expiresAt] = OffsetDateTime.now().minusDays(1)
            }
        }
        assertEquals(HttpStatusCode.Created, client.invite(token, email, listOf(b)).status)

        val folders = transaction {
            MaterialFolderTable.selectAll().where { MaterialFolderTable.name eq "Onboarding – $email" }.count()
        }
        assertEquals(1, folders)
    }

    @Test
    fun `invite with another teacher's material is 404`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val foreign = seedMaterial(owner = seedTeacher())
        val email = "${Uuid.random()}@test.com"

        val response = client.invite(token, email, listOf(seedMaterial(), foreign))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(transaction { RegistrationTable.selectAll().where { RegistrationTable.email eq email }.empty() })
    }

    @Test
    fun `invite with a link material is 400`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.invite(token, "${Uuid.random()}@test.com", listOf(seedMaterial(type = MaterialType.LINK)))

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invite with more than five materials is 400`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val ids = (1..6).map { seedMaterial(name = "M$it") }

        val response = client.invite(token, "${Uuid.random()}@test.com", ids)

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invite with an invalid material id is 400`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)

        val response = client.post("/api/v1/registration/invite") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(InviteUserRequest(email = "${Uuid.random()}@test.com", role = UserRole.STUDENT, materialIds = listOf("nope")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `admin invite with materials is 400`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.invite(token, "${Uuid.random()}@test.com", listOf(seedMaterial()))

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `level on a teacher invite is 400`() = testApp {
        val client = createJsonClient(this)
        val token = getAdminToken(client)

        val response = client.invite(token, "${Uuid.random()}@test.com", level = LanguageLevel.A1, role = UserRole.TEACHER)

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `signup shares the invite materials, sets user_id and copies the level`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val a = seedMaterial(name = "A")
        val b = seedMaterial(name = "B")
        val registrationToken = client.invite(token, email, listOf(a, b), LanguageLevel.A2).body<InviteUserResponse>().token

        assertEquals(HttpStatusCode.Created, client.register(registrationToken, email).status)

        val user = userByEmail(email)
        val userId = user[UserTable.id].value
        assertEquals(LanguageLevel.A2, user[UserTable.level])
        val registration = transaction { RegistrationTable.selectAll().where { RegistrationTable.email eq email }.single() }
        assertEquals(userId, registration[RegistrationTable.userId]?.value)

        val studentToken = getToken(client, email)
        val visible = client.get("/api/v1/materials") { bearerAuth(studentToken) }
            .body<MaterialPageResponse>().materials.map { UUID.fromString(it.id) }.toSet()
        assertEquals(setOf(a, b), visible)
        transaction {
            MaterialShareTable.selectAll().where { MaterialShareTable.studentId eq userId }.forEach {
                assertEquals(teacher, it[MaterialShareTable.sharedBy].value)
            }
        }
        val notifications = client.get("/api/v1/notifications") { bearerAuth(studentToken) }.body<String>()
        assertTrue("MATERIAL_SHARED" !in notifications, "No share notifications expected: $notifications")
    }

    @Test
    fun `signup shares the onboarding folder so the student sees it in the folder tree`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val a = seedMaterial(name = "A")
        val b = seedMaterial(name = "B")
        val registrationToken = client.invite(token, email, listOf(a, b)).body<InviteUserResponse>().token

        assertEquals(HttpStatusCode.Created, client.register(registrationToken, email).status)

        val folderId = transaction {
            MaterialFolderTable.selectAll().where { MaterialFolderTable.name eq "Onboarding – $email" }.single()[MaterialFolderTable.id].value
        }
        val studentToken = getToken(client, email)
        val tree = client.get("/api/v1/material-folders?tree=true") { bearerAuth(studentToken) }.body<List<FolderTreeNode>>()
        assertEquals(listOf(folderId.toString()), tree.map { it.folder.id })
        val inFolder = client.get("/api/v1/materials?folderId=$folderId") { bearerAuth(studentToken) }
            .body<MaterialPageResponse>().materials.map { UUID.fromString(it.id) }.toSet()
        assertEquals(setOf(a, b), inFolder)
        transaction {
            val share = FolderShareTable.selectAll().where { FolderShareTable.folderId eq folderId }.single()
            assertEquals(teacher, share[FolderShareTable.sharedBy].value)
        }
    }

    @Test
    fun `material moved out of the onboarding folder before signup does not share its new folder`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val a = seedMaterial(name = "A")
        val b = seedMaterial(name = "B")
        val registrationToken = client.invite(token, email, listOf(a, b)).body<InviteUserResponse>().token
        val (onboarding, other) = transaction {
            val onboarding = MaterialFolderTable.selectAll()
                .where { MaterialFolderTable.name eq "Onboarding – $email" }
                .single()[MaterialFolderTable.id].value
            val other = MaterialFolderTable.insertAndGetId {
                it[teacherId] = teacher
                it[name] = "Private"
                it[createdAt] = OffsetDateTime.now()
                it[updatedAt] = OffsetDateTime.now()
            }.value
            MaterialTable.update({ MaterialTable.id eq b }) { it[folderId] = other }
            onboarding to other
        }

        assertEquals(HttpStatusCode.Created, client.register(registrationToken, email).status)

        val shared = sharedFoldersWith(userByEmail(email)[UserTable.id].value)
        assertEquals(setOf(onboarding), shared)
        assertTrue(other !in shared)
    }

    @Test
    fun `material deleted before signup is skipped and the rest are shared`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val a = seedMaterial(name = "A")
        val b = seedMaterial(name = "B")
        val registrationToken = client.invite(token, email, listOf(a, b)).body<InviteUserResponse>().token
        transaction { MaterialTable.deleteWhere { MaterialTable.id eq a } }

        assertEquals(HttpStatusCode.Created, client.register(registrationToken, email).status)

        assertEquals(setOf(b), sharedWith(userByEmail(email)[UserTable.id].value))
    }

    @Test
    fun `re-invite after expiry shares only the new invite's materials`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        val old = seedMaterial(name = "Old")
        val new = seedMaterial(name = "New")
        assertEquals(HttpStatusCode.Created, client.invite(token, email, listOf(old)).status)
        transaction {
            RegistrationTable.update({ RegistrationTable.email eq email }) {
                it[expiresAt] = OffsetDateTime.now().minusDays(1)
            }
        }
        val registrationToken = client.invite(token, email, listOf(new)).body<InviteUserResponse>().token

        assertEquals(HttpStatusCode.Created, client.register(registrationToken, email).status)

        assertEquals(setOf(new), sharedWith(userByEmail(email)[UserTable.id].value))
    }

    @Test
    fun `invite without materials creates no links, folders or shares`() = testApp {
        val client = createJsonClient(this)
        val token = getTeacherToken(client)
        val email = "${Uuid.random()}@test.com"
        seedMaterial()
        val registrationToken = client.invite(token, email).body<InviteUserResponse>().token

        assertEquals(HttpStatusCode.Created, client.register(registrationToken, email).status)

        val user = userByEmail(email)
        assertNull(user[UserTable.level])
        assertEquals(emptySet(), sharedWith(user[UserTable.id].value))
        assertEquals(emptySet(), sharedFoldersWith(user[UserTable.id].value))
        assertEquals(emptySet(), joinedMaterials(email))
        assertTrue(transaction { MaterialFolderTable.selectAll().empty() })
    }
}

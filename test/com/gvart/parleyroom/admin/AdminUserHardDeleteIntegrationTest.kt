package com.gvart.parleyroom.admin

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.admin.transfer.AdminUserResponse
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.storage.StorageConfig
import com.gvart.parleyroom.common.storage.StorageService
import com.gvart.parleyroom.document.data.DocumentAudience
import com.gvart.parleyroom.document.data.DocumentStudentTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.document.transfer.DocumentResponse
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.GoalType
import com.gvart.parleyroom.goal.transfer.GoalResponse
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.homework.data.HomeworkUploadTable
import com.gvart.parleyroom.homework.transfer.HomeworkPageResponse
import com.gvart.parleyroom.lesson.data.LessonEventTable
import com.gvart.parleyroom.lesson.data.LessonEventType
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.transfer.LessonPageResponse
import com.gvart.parleyroom.material.data.FolderShareTable
import com.gvart.parleyroom.material.data.LessonMaterialTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.material.data.MaterialType
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonArray
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AdminUserHardDeleteIntegrationTest : IntegrationTest() {

    private val teacher = UUID.fromString(TEACHER_ID)
    private val student = UUID.fromString(STUDENT_ID)
    private val student2 = UUID.fromString(STUDENT_2_ID)

    private val storage by lazy {
        StorageService(
            StorageConfig(
                endpoint = minioEndpoint,
                region = "us-east-1",
                accessKey = "minioadmin",
                secretKey = "minioadmin",
                bucket = "parleyroom-test",
                maxFileSize = 104857600,
                pathStyleAccess = true,
            )
        )
    }

    @Test
    fun `hard-deleting a student removes their data and keeps group lessons`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val uploadKey = "homework/${UUID.randomUUID()}/answer.txt"
        storeObject(uploadKey)

        val assignment = assignment()
        val item = taskItem(assignment)
        val homework = homework(assignment, student)
        homework(assignment, student2)
        transaction {
            HomeworkUploadTable.insert {
                it[homeworkId] = homework
                it[assignmentItemId] = item
                it[storageKey] = uploadKey
                it[fileName] = "answer.txt"
                it[contentType] = "text/plain"
                it[size] = 2
                it[createdAt] = OffsetDateTime.now()
            }
        }
        goal(student)
        val entry = vocabEntry()
        transaction {
            StudentVocabTable.insert {
                it[studentId] = student
                it[vocabEntryId] = entry
                it[addedAt] = OffsetDateTime.now()
            }
        }
        val oneOnOne = lesson(LessonType.ONE_ON_ONE, students = listOf(student))
        val club = lesson(LessonType.SPEAKING_CLUB, students = listOf(student, student2), createdBy = student)
        transaction {
            LessonEventTable.insert {
                it[lessonId] = club
                it[eventType] = LessonEventType.RESCHEDULE_REQUESTED
                it[actorId] = student
                it[newScheduledAt] = OffsetDateTime.now().plusDays(3)
                it[createdAt] = OffsetDateTime.now()
            }
        }

        hardDelete(client, STUDENT_ID)

        transaction {
            assertEquals(0, count(UserTable) { UserTable.id eq student })
            assertEquals(0, count(HomeworkTable) { HomeworkTable.studentId eq student })
            assertEquals(1, count(HomeworkTable) { HomeworkTable.studentId eq student2 })
            assertEquals(0, count(GoalTable) { GoalTable.studentId eq student })
            assertEquals(0, count(StudentVocabTable) { StudentVocabTable.studentId eq student })
            assertEquals(1, count(VocabEntryTable) { VocabEntryTable.id eq entry })
            assertEquals(0, count(LessonTable) { LessonTable.id eq oneOnOne })

            val clubRow = LessonTable.selectAll().where { LessonTable.id eq club }.single()
            assertNull(clubRow[LessonTable.createdBy])
            assertEquals(
                listOf(student2),
                LessonStudentTable.selectAll().where { LessonStudentTable.lessonId eq club }.map { it[LessonStudentTable.studentId].value },
            )
            assertNull(LessonEventTable.selectAll().where { LessonEventTable.lessonId eq club }.single()[LessonEventTable.actorId])
        }
        assertFalse(objectExists(uploadKey))

        // The surviving club still renders for the other student.
        val lessons = client.get("/api/v1/lessons") { bearerAuth(getStudent2Token(client)) }
        assertEquals(HttpStatusCode.OK, lessons.status)
        val listed = lessons.body<LessonPageResponse>().lessons.single { it.id == club.toString() }
        assertNull(listed.createdBy)
        assertNull(listed.pendingReschedule?.requestedBy)
    }

    @Test
    fun `hard-deleting a teacher keeps what students use and removes the rest`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val privateKey = "materials/$teacher/${UUID.randomUUID()}/private.pdf"
        storeObject(privateKey)

        val studentLesson = lesson(LessonType.ONE_ON_ONE, students = listOf(student))
        val emptyClub = lesson(LessonType.SPEAKING_CLUB, students = emptyList())

        val usedAssignment = assignment()
        val unusedAssignment = assignment()
        homework(usedAssignment, student)
        val goal = goal(student)

        val privateParent = folder("Private parent")
        val sharedFolder = folder("Shared", parent = privateParent)
        val sharedChild = folder("Shared child", parent = sharedFolder)
        val privateFolder = folder("Private")
        transaction {
            FolderShareTable.insert {
                it[folderId] = sharedFolder
                it[studentId] = student
                it[sharedBy] = teacher
                it[sharedAt] = OffsetDateTime.now()
            }
        }

        val privateMaterial = material("private", key = privateKey, folder = privateFolder)
        val sharedMaterial = material("shared")
        val inSharedFolder = material("in shared folder", folder = sharedChild)
        val onStudentLesson = material("on student lesson")
        val onEmptyClub = material("on empty club")
        val inHomework = material("in homework")
        transaction {
            MaterialShareTable.insert {
                it[materialId] = sharedMaterial
                it[studentId] = student
                it[sharedBy] = teacher
                it[sharedAt] = OffsetDateTime.now()
            }
            listOf(studentLesson to onStudentLesson, emptyClub to onEmptyClub).forEach { (lesson, material) ->
                LessonMaterialTable.insert {
                    it[lessonId] = lesson
                    it[materialId] = material
                    it[attachedBy] = teacher
                    it[attachedAt] = OffsetDateTime.now()
                }
            }
        }
        taskItem(usedAssignment, material = inHomework)

        val studentWord = vocabEntry("Haus")
        vocabEntry("Maus")
        val sharedDoc = document("Shared")
        document("Private")
        transaction {
            StudentVocabTable.insert {
                it[studentId] = student
                it[vocabEntryId] = studentWord
                it[addedAt] = OffsetDateTime.now()
            }
            DocumentStudentTable.insert {
                it[documentId] = sharedDoc
                it[studentId] = student
                it[sharedAt] = OffsetDateTime.now()
            }
        }

        hardDelete(client, TEACHER_ID)

        transaction {
            assertNull(LessonTable.selectAll().where { LessonTable.id eq studentLesson }.single()[LessonTable.teacherId])
            assertEquals(0, count(LessonTable) { LessonTable.id eq emptyClub })
            assertNull(AssignmentTable.selectAll().where { AssignmentTable.id eq usedAssignment }.single()[AssignmentTable.teacherId])
            assertEquals(0, count(AssignmentTable) { AssignmentTable.id eq unusedAssignment })
            assertEquals(1, count(HomeworkTable) { HomeworkTable.studentId eq student })
            assertNull(GoalTable.selectAll().where { GoalTable.id eq goal }.single()[GoalTable.teacherId])

            val materials = MaterialTable.selectAll().associate { it[MaterialTable.id].value to it }
            assertEquals(setOf(sharedMaterial, inSharedFolder, onStudentLesson, inHomework), materials.keys)
            assertTrue(materials.values.all { it[MaterialTable.teacherId] == null })
            assertNull(LessonMaterialTable.selectAll().single()[LessonMaterialTable.attachedBy])
            assertNull(MaterialShareTable.selectAll().single()[MaterialShareTable.sharedBy])

            val folders = MaterialFolderTable.selectAll().associate { it[MaterialFolderTable.id].value to it }
            assertEquals(setOf(sharedFolder, sharedChild), folders.keys)
            assertNull(folders.getValue(sharedFolder)[MaterialFolderTable.parentFolderId])
            assertEquals(sharedFolder, folders.getValue(sharedChild)[MaterialFolderTable.parentFolderId]?.value)

            val words = VocabEntryTable.selectAll().associate { it[VocabEntryTable.id].value to it }
            assertEquals(setOf(studentWord), words.keys)
            assertNull(words.getValue(studentWord)[VocabEntryTable.teacherId])
            assertEquals(1, count(StudentVocabTable) { StudentVocabTable.studentId eq student })
            assertEquals(listOf(sharedDoc), DocumentTable.selectAll().map { it[DocumentTable.id].value })
        }
        assertFalse(objectExists(privateKey))

        // The student's views render with an empty teacher.
        val studentToken = getStudentToken(client)
        val lesson = client.get("/api/v1/lessons") { bearerAuth(studentToken) }
            .body<LessonPageResponse>().lessons.single { it.id == studentLesson.toString() }
        assertNull(lesson.teacherId)
        assertNull(lesson.teacher)
        val homework = client.get("/api/v1/homework") { bearerAuth(studentToken) }.body<HomeworkPageResponse>()
        assertNull(homework.homework.single().teacher)
        val goals = client.get("/api/v1/goals") { bearerAuth(studentToken) }.body<List<GoalResponse>>()
        assertNull(goals.single().teacherId)
        val vocab = client.get("/api/v1/vocabulary") { bearerAuth(studentToken) }.body<StudentVocabPageResponse>()
        assertEquals(listOf("Haus"), vocab.words.map { it.lemma })
        val doc = client.get("/api/v1/documents/$sharedDoc") { bearerAuth(studentToken) }
        assertEquals(HttpStatusCode.OK, doc.status)
        assertNull(doc.body<DocumentResponse>().ownerId)
    }

    @Test
    fun `hard-deleting a teacher without data succeeds`() = testApp {
        val client = createJsonClient(this)
        startApplication() // loads the test data before seeding
        val created = client.post("/api/v1/admin/users") {
            bearerAuth(getAdminToken(client))
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "email" to "empty-teacher-${Uuid.random()}@test.com", "firstName" to "Empty", "lastName" to "Teacher",
                "role" to "TEACHER", "password" to "tempuserpw"
            ))
        }.body<AdminUserResponse>()

        hardDelete(client, created.id)

        val get = client.get("/api/v1/admin/users/${created.id}") { bearerAuth(getAdminToken(client)) }
        assertEquals(HttpStatusCode.NotFound, get.status)
    }

    private suspend fun hardDelete(client: HttpClient, id: String) {
        val response = client.delete("/api/v1/admin/users/$id?hard=true") { bearerAuth(getAdminToken(client)) }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    private fun count(table: Table, where: () -> org.jetbrains.exposed.v1.core.Op<Boolean>): Long =
        table.selectAll().where { where() }.count()

    private fun storeObject(key: String) {
        val bytes = "hi".toByteArray()
        storage.upload(key, "text/plain", bytes.inputStream(), bytes.size.toLong())
    }

    private fun objectExists(key: String): Boolean = runCatching { storage.stream(key).use { } }.isSuccess

    private fun lesson(type: LessonType, students: List<UUID>, createdBy: UUID = teacher): UUID = transaction {
        val now = OffsetDateTime.now()
        val id = LessonTable.insertAndGetId {
            it[title] = "Lesson"
            it[LessonTable.type] = type
            it[scheduledAt] = now.plusDays(2)
            it[teacherId] = teacher
            it[topic] = "Topic"
            it[LessonTable.createdBy] = createdBy
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        students.forEach { sid ->
            LessonStudentTable.insert {
                it[lessonId] = id
                it[studentId] = sid
            }
        }
        id
    }

    private fun assignment(): UUID = transaction {
        AssignmentTable.insertAndGetId {
            it[teacherId] = teacher
            it[title] = "Assignment"
            it[itemCount] = 1
            it[totalUnits] = 1
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    private fun taskItem(assignment: UUID, material: UUID? = null): UUID = transaction {
        AssignmentItemTable.insertAndGetId {
            it[assignmentId] = assignment
            it[position] = AssignmentItemTable.selectAll().where { AssignmentItemTable.assignmentId eq assignment }.count().toInt()
            it[kind] = if (material != null) AssignmentItemKind.MATERIAL else AssignmentItemKind.TASK
            it[title] = "Item"
            it[materialId] = material
        }.value
    }

    private fun homework(assignment: UUID, studentId: UUID): UUID = transaction {
        HomeworkTable.insertAndGetId {
            it[assignmentId] = assignment
            it[HomeworkTable.studentId] = studentId
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    private fun goal(studentId: UUID): UUID = transaction {
        GoalTable.insertAndGetId {
            it[GoalTable.studentId] = studentId
            it[teacherId] = teacher
            it[type] = GoalType.LEVEL
            it[targetLevel] = LanguageLevel.B1
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    private fun vocabEntry(lemma: String = "Haus"): UUID = transaction {
        VocabEntryTable.insertAndGetId {
            it[teacherId] = teacher
            it[VocabEntryTable.lemma] = lemma
            it[wordType] = WordType.NOUN
            it[translations] = emptyMap()
            it[synonyms] = emptyList()
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    private fun document(title: String): UUID = transaction {
        DocumentTable.insertAndGetId {
            it[ownerId] = teacher
            it[DocumentTable.title] = title
            it[audience] = DocumentAudience.STUDENT
            it[blocks] = JsonArray(emptyList())
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    private fun folder(name: String, parent: UUID? = null): UUID = transaction {
        MaterialFolderTable.insertAndGetId {
            it[teacherId] = teacher
            it[MaterialFolderTable.name] = name
            it[parentFolderId] = parent
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    private fun material(name: String, key: String? = null, folder: UUID? = null): UUID = transaction {
        MaterialTable.insertAndGetId {
            it[teacherId] = teacher
            it[MaterialTable.name] = name
            it[type] = if (key != null) MaterialType.PDF else MaterialType.LINK
            it[url] = key ?: "https://example.com/$name"
            it[folderId] = folder
            it[createdAt] = OffsetDateTime.now()
        }.value
    }
}

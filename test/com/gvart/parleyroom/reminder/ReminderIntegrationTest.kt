package com.gvart.parleyroom.reminder

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.email.data.EmailLogTable
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.group.data.GroupType
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.reminder.service.ReminderService
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderIntegrationTest : IntegrationTest() {

    private val teacher = UUID.fromString(TEACHER_ID)
    private val student = UUID.fromString(STUDENT_ID)
    private val student2 = UUID.fromString(STUDENT_2_ID)

    /** Monday; Berlin is UTC+1, New York (DST since 10 March) UTC−4. */
    private val now = OffsetDateTime.parse("2030-03-11T12:00:00Z")

    private suspend fun ApplicationTestBuilder.reminders(): ReminderService {
        startApplication()
        return application.dependencies.resolve()
    }

    private fun recipients() = sentEmails.sent.map { it.to }.sorted()

    private fun seedLesson(
        scheduledAt: OffsetDateTime,
        status: LessonStatus = LessonStatus.CONFIRMED,
        type: LessonType = LessonType.ONE_ON_ONE,
        groupId: UUID? = null,
        students: Map<UUID, LessonStudentStatus> = mapOf(student to LessonStudentStatus.CONFIRMED),
    ): UUID = transaction {
        val id = LessonTable.insertAndGetId {
            it[title] = "Perfekt"
            it[LessonTable.type] = type
            it[LessonTable.scheduledAt] = scheduledAt
            it[teacherId] = teacher
            it[LessonTable.status] = status
            it[topic] = "Alltag"
            it[LessonTable.groupId] = groupId
            it[createdBy] = teacher
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        for ((studentId, studentStatus) in students) {
            LessonStudentTable.insert {
                it[lessonId] = id
                it[LessonStudentTable.studentId] = studentId
                it[LessonStudentTable.status] = studentStatus
            }
        }
        id
    }

    private fun seedHomework(
        dueDate: LocalDate?,
        studentId: UUID = student,
        status: HomeworkStatus = HomeworkStatus.OPEN,
        title: String = "Modalverben",
    ): UUID = transaction {
        val assignment = AssignmentTable.insertAndGetId {
            it[teacherId] = teacher
            it[AssignmentTable.title] = title
            it[AssignmentTable.dueDate] = dueDate?.toKotlinLocalDate()
            it[itemCount] = 1
            it[totalUnits] = 1
            it[createdAt] = now
            it[updatedAt] = now
        }
        HomeworkTable.insertAndGetId {
            it[assignmentId] = assignment
            it[HomeworkTable.studentId] = studentId
            it[HomeworkTable.status] = status
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    private fun updateUser(userId: UUID, block: UserTable.(org.jetbrains.exposed.v1.core.statements.UpdateStatement) -> Unit) =
        transaction { UserTable.update({ UserTable.id eq userId }) { block(it) } }

    // -------- Lessons --------

    @Test
    fun `one-on-one lesson reminds teacher and student 24h and 1h before, once each`() = testApp {
        val service = reminders()
        val start = now.plusHours(22)
        seedLesson(start)

        service.runOnce(now)
        assertEquals(listOf("student@test.com", "teacher@test.com"), recipients())
        val toTeacher = sentEmails.sent.single { it.to == "teacher@test.com" }
        val toStudent = sentEmails.sent.single { it.to == "student@test.com" }
        assertTrue("Test Student" in toTeacher.text, toTeacher.text)
        assertTrue("Test Teacher" in toStudent.text, toStudent.text)

        service.runOnce(now.plusMinutes(5))
        assertEquals(2, sentEmails.sent.size)

        service.runOnce(start.minusMinutes(50))
        service.runOnce(start.minusMinutes(45))
        assertEquals(4, sentEmails.sent.size)
        assertEquals(listOf("student@test.com", "teacher@test.com"), sentEmails.sent.drop(2).map { it.to }.sorted())

        service.runOnce(start.plusMinutes(1))
        assertEquals(4, sentEmails.sent.size)
    }

    @Test
    fun `group lesson reminds every confirmed participant, teacher sees the group name`() = testApp {
        val service = reminders()
        val group = transaction {
            GroupTable.insertAndGetId {
                it[teacherId] = teacher
                it[name] = "Die Robben"
                it[type] = GroupType.SPEECH
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        }
        seedLesson(
            now.plusMinutes(30), type = LessonType.SPEAKING_CLUB, groupId = group,
            students = mapOf(student to LessonStudentStatus.CONFIRMED, student2 to LessonStudentStatus.CONFIRMED),
        )

        service.runOnce(now)
        assertEquals(listOf("student2@test.com", "student@test.com", "teacher@test.com"), recipients())
        assertTrue("Die Robben" in sentEmails.sent.single { it.to == "teacher@test.com" }.text)
    }

    @Test
    fun `requested participants and inactive or opted-out users get nothing`() = testApp {
        val service = reminders()
        seedLesson(
            now.plusMinutes(30), type = LessonType.SPEAKING_CLUB,
            students = mapOf(student to LessonStudentStatus.CONFIRMED, student2 to LessonStudentStatus.REQUESTED),
        )
        updateUser(student) { it[emailLessonReminders] = false }

        service.runOnce(now)
        assertEquals(listOf("teacher@test.com"), recipients())

        sentEmails.sent.clear()
        updateUser(student) { it[emailLessonReminders] = true }
        updateUser(teacher) { it[status] = UserStatus.INACTIVE }
        seedLesson(now.plusMinutes(40))
        service.runOnce(now)
        assertEquals(listOf("student@test.com", "student@test.com"), recipients())
    }

    @Test
    fun `a lesson booked 3h ahead gets only the 1h reminder`() = testApp {
        val service = reminders()
        val start = now.plusHours(3)
        seedLesson(start)

        service.runOnce(now)
        service.runOnce(start.minusHours(2))
        assertTrue(sentEmails.sent.isEmpty())

        service.runOnce(start.minusMinutes(59))
        assertEquals(2, sentEmails.sent.size)
        val variants = transaction { EmailLogTable.selectAll().map { it[EmailLogTable.variant]!! } }
        assertTrue(variants.all { it.startsWith("H1@") }, variants.toString())
    }

    @Test
    fun `cancelled, requested, running and past lessons are skipped`() = testApp {
        val service = reminders()
        seedLesson(now.plusMinutes(30), status = LessonStatus.CANCELLED)
        seedLesson(now.plusMinutes(30), status = LessonStatus.REQUEST)
        seedLesson(now.plusHours(22), status = LessonStatus.REQUEST)
        seedLesson(now.minusMinutes(10), status = LessonStatus.IN_PROGRESS)
        seedLesson(now.minusMinutes(10))

        service.runOnce(now)
        assertTrue(sentEmails.sent.isEmpty())
    }

    @Test
    fun `a lesson moved after its 24h reminder is reminded again for the new time`() = testApp {
        val service = reminders()
        val lesson = seedLesson(now.plusHours(22))
        service.runOnce(now)
        assertEquals(2, sentEmails.sent.size)

        val newStart = now.plusDays(2)
        transaction { LessonTable.update({ LessonTable.id eq lesson }) { it[scheduledAt] = newStart } }
        service.runOnce(now.plusMinutes(5))
        assertEquals(2, sentEmails.sent.size)

        service.runOnce(newStart.minusHours(23))
        assertEquals(4, sentEmails.sent.size)
    }

    // -------- Homework --------

    @Test
    fun `due tomorrow goes out once from 09 00 local time`() = testApp {
        val service = reminders()
        seedHomework(LocalDate.parse("2030-03-12"))

        service.runOnce(OffsetDateTime.parse("2030-03-11T07:30:00Z")) // 08:30 in Berlin
        assertTrue(sentEmails.sent.isEmpty())

        service.runOnce(OffsetDateTime.parse("2030-03-11T08:05:00Z")) // 09:05 in Berlin
        service.runOnce(OffsetDateTime.parse("2030-03-11T15:00:00Z"))
        val email = sentEmails.sent.single()
        assertEquals("student@test.com", email.to)
        assertTrue("Modalverben" in email.subject)
    }

    @Test
    fun `students in other timezones get it at their own 09 00`() = testApp {
        val service = reminders()
        updateUser(student2) { it[timezone] = "America/New_York" }
        seedHomework(LocalDate.parse("2030-03-12"), studentId = student2)

        service.runOnce(OffsetDateTime.parse("2030-03-11T08:30:00Z")) // 04:30 in New York
        assertTrue(sentEmails.sent.isEmpty())

        service.runOnce(OffsetDateTime.parse("2030-03-11T13:05:00Z")) // 09:05 in New York
        assertEquals("student2@test.com", sentEmails.sent.single().to)
    }

    @Test
    fun `finished homework and homework without a due date get nothing`() = testApp {
        val service = reminders()
        seedHomework(LocalDate.parse("2030-03-12"), status = HomeworkStatus.SUBMITTED)
        seedHomework(LocalDate.parse("2030-03-12"), status = HomeworkStatus.REVIEWED)
        seedHomework(LocalDate.parse("2030-03-12"), status = HomeworkStatus.DONE)
        seedHomework(LocalDate.parse("2030-03-10"), status = HomeworkStatus.DONE)
        seedHomework(null)

        service.runOnce(OffsetDateTime.parse("2030-03-11T10:00:00Z"))
        assertTrue(sentEmails.sent.isEmpty())
    }

    @Test
    fun `overdue goes out once, the day after the due date`() = testApp {
        val service = reminders()
        seedHomework(LocalDate.parse("2030-03-10"))

        service.runOnce(OffsetDateTime.parse("2030-03-10T12:00:00Z")) // due today: nothing
        service.runOnce(OffsetDateTime.parse("2030-03-11T07:00:00Z")) // 08:00 in Berlin
        assertTrue(sentEmails.sent.isEmpty())

        service.runOnce(OffsetDateTime.parse("2030-03-11T09:00:00Z"))
        service.runOnce(OffsetDateTime.parse("2030-03-11T18:00:00Z"))
        service.runOnce(OffsetDateTime.parse("2030-03-12T09:00:00Z"))
        val email = sentEmails.sent.single()
        assertTrue("Modalverben" in email.subject)
        assertTrue("Просроченное" in email.subject, email.subject)
    }

    @Test
    fun `homework reminders use the student's language`() = testApp {
        val service = reminders()
        updateUser(student) { it[nativeLanguage] = "uk" }
        seedHomework(LocalDate.parse("2030-03-12"))

        service.runOnce(OffsetDateTime.parse("2030-03-11T10:00:00Z"))
        assertEquals("Домашнє завдання на завтра: Modalverben", sentEmails.sent.single().subject)
    }
}

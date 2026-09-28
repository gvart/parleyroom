package com.gvart.parleyroom.progress

import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.Sql
import com.gvart.parleyroom.homework.data.AssignmentItemKind
import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.AutoResult
import com.gvart.parleyroom.homework.data.HomeworkAnswerTable
import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.util.UUID

/** One homework unit: the auto result and an optional teacher verdict. */
data class HwUnit(val auto: AutoResult, val teacher: Boolean? = null)

val RIGHT = HwUnit(AutoResult.CORRECT)
val WRONG = HwUnit(AutoResult.INCORRECT)

/** Direct DB seeding of homework and students for progress / goal tests. */
object ProgressFixtures {

    fun setLevel(studentId: UUID, level: LanguageLevel?) = transaction {
        UserTable.update({ UserTable.id eq studentId }) { it[UserTable.level] = level }
    }

    /**
     * One assignment of the teacher with a single DOCUMENT ([documentId]) or MATERIAL ([materialId])
     * item and one homework for [studentId] with the given [units] as answers.
     */
    fun homework(
        studentId: UUID,
        documentId: UUID? = null,
        materialId: UUID? = null,
        status: HomeworkStatus = HomeworkStatus.REVIEWED,
        attempt: Int = 1,
        submittedAt: OffsetDateTime? = OffsetDateTime.now().minusDays(1),
        units: List<HwUnit> = emptyList(),
        teacherId: UUID = TEACHER,
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        val assignmentId = AssignmentTable.insertAndGetId {
            it[AssignmentTable.teacherId] = teacherId
            it[title] = "Hausaufgabe"
            it[itemCount] = 1
            it[totalUnits] = units.size
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        val itemId = AssignmentItemTable.insertAndGetId {
            it[AssignmentItemTable.assignmentId] = assignmentId
            it[position] = 0
            it[kind] = if (documentId != null) AssignmentItemKind.DOCUMENT else AssignmentItemKind.MATERIAL
            it[title] = "Übung"
            it[AssignmentItemTable.documentId] = documentId
            it[documentRevision] = documentId?.let { 1 }
            it[AssignmentItemTable.materialId] = materialId
        }.value
        val homeworkId = HomeworkTable.insertAndGetId {
            it[HomeworkTable.assignmentId] = assignmentId
            it[HomeworkTable.studentId] = studentId
            it[HomeworkTable.status] = status
            it[HomeworkTable.attempt] = attempt
            it[HomeworkTable.submittedAt] = submittedAt
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        units.forEach { unit ->
            HomeworkAnswerTable.insert {
                it[HomeworkAnswerTable.homeworkId] = homeworkId
                it[assignmentItemId] = itemId
                it[blockId] = if (documentId != null) UUID.randomUUID() else null
                it[itemRef] = if (documentId != null) UUID.randomUUID() else null
                it[autoResult] = unit.auto
                it[teacherCorrect] = unit.teacher
            }
        }
        homeworkId
    }

    /** A new student linked to the test teacher (login <email> / the test password). */
    fun linkedStudent(email: String, firstName: String = "Neu", lastName: String = "Schüler"): UUID = transaction {
        val passwordHash = UserTable.selectAll().where { UserTable.id eq TEACHER }.single()[UserTable.passwordHash]
        val now = OffsetDateTime.now()
        val id = UserTable.insertAndGetId {
            it[UserTable.email] = email
            it[UserTable.firstName] = firstName
            it[UserTable.lastName] = lastName
            it[role] = UserRole.STUDENT
            it[UserTable.passwordHash] = passwordHash
            it[initials] = "NS"
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        Sql.update(
            "INSERT INTO teacher_students (teacher_id, student_id, lesson_types, status, started_at) " +
                    "VALUES (?, ?, '{ONE_ON_ONE}', 'ACTIVE', now())",
            TEACHER, id,
        )
        id
    }
}

package com.gvart.parleyroom.lessonprep

import com.gvart.parleyroom.admin.data.FeatureFlagTable
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.goal.GoalFixtures
import com.gvart.parleyroom.goal.data.MilestoneSelfAssessmentTable
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.SelfAssessmentAnswer
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lessonprep.data.LessonPlanStatus
import com.gvart.parleyroom.lessonprep.data.LessonPlanTable
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanContent
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.OffsetDateTime
import java.util.UUID

/** Lessons, roadmaps and plans seeded directly (the API is covered by the integration tests). */
object LessonPrepFixtures {

    /** Feature flag `lessonPrep` on (TRUNCATE users CASCADE turns it off again before every test). */
    fun enable() = transaction {
        FeatureFlagTable.upsert {
            it[name] = "lessonPrep"
            it[enabled] = true
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun lesson(
        at: OffsetDateTime,
        status: LessonStatus = LessonStatus.COMPLETED,
        student: UUID = STUDENT,
        teacher: UUID = TEACHER,
        notes: String? = null,
        minutes: Int = 60,
        title: String = "Lektion",
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        val id = LessonTable.insertAndGetId {
            it[LessonTable.title] = title
            it[type] = LessonType.ONE_ON_ONE
            it[scheduledAt] = at
            it[durationMinutes] = minutes
            it[teacherId] = teacher
            it[LessonTable.status] = status
            it[topic] = "Alltag"
            it[rawNotes] = notes
            it[createdBy] = teacher
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        LessonStudentTable.insert {
            it[lessonId] = id
            it[studentId] = student
            it[LessonStudentTable.status] = LessonStudentStatus.CONFIRMED
        }
        id
    }

    fun plan(
        lessonId: UUID?,
        student: UUID = STUDENT,
        teacher: UUID = TEACHER,
        goalId: UUID? = null,
        content: LessonPlanContent? = null,
        coveredNote: String? = null,
        status: LessonPlanStatus = LessonPlanStatus.DONE,
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        LessonPlanTable.insertAndGetId {
            it[studentId] = student
            it[teacherId] = teacher
            it[LessonPlanTable.goalId] = goalId
            it[LessonPlanTable.lessonId] = lessonId
            it[prepNote] = "Notiz"
            it[plan] = content
            it[LessonPlanTable.coveredNote] = coveredNote
            it[LessonPlanTable.status] = status
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    /** The student's homework from a new assignment of [lessonId]. */
    fun homework(lessonId: UUID, title: String, student: UUID = STUDENT): UUID = transaction {
        val now = OffsetDateTime.now()
        val assignment = AssignmentTable.insertAndGetId {
            it[teacherId] = TEACHER
            it[AssignmentTable.lessonId] = lessonId
            it[AssignmentTable.title] = title
            it[itemCount] = 0
            it[totalUnits] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        HomeworkTable.insertAndGetId {
            it[assignmentId] = assignment
            it[studentId] = student
            it[createdAt] = now
            it[updatedAt] = now
        }.value
    }

    /** Item ids of a milestone in position order. */
    fun items(milestoneId: UUID): List<UUID> = transaction {
        RoadmapItemTable.selectAll().where { RoadmapItemTable.milestoneId eq milestoneId }
            .orderBy(RoadmapItemTable.position, SortOrder.ASC).map { it[RoadmapItemTable.id].value }
    }

    fun checkItem(itemId: UUID, lessonId: UUID, state: RoadmapItemState = RoadmapItemState.DONE) = transaction {
        RoadmapItemTable.update({ RoadmapItemTable.id eq itemId }) {
            it[RoadmapItemTable.state] = state
            it[RoadmapItemTable.lessonId] = lessonId
        }
    }

    class Roadmap(val goalId: UUID, val done: UUID, val current: UUID, val next: UUID)

    /**
     * An exam goal (interests "Schach") with a done milestone the student answered PARTLY ("Test …"),
     * a current one with items NOT_STARTED, IN_PROGRESS, NOT_STARTED and a next one.
     */
    fun roadmap(student: UUID = STUDENT): Roadmap {
        val goal = GoalFixtures.goal(studentId = student, teacherInput = true)
        val done = GoalFixtures.milestone(goal, 1, listOf(RoadmapItemState.DONE, RoadmapItemState.DONE), done = true, title = "Begrüßung")
        val current = GoalFixtures.milestone(
            goal, 2, listOf(RoadmapItemState.NOT_STARTED, RoadmapItemState.IN_PROGRESS, RoadmapItemState.NOT_STARTED), title = "Familie",
        )
        val next = GoalFixtures.milestone(goal, 3, listOf(RoadmapItemState.NOT_STARTED), title = "Einkaufen")
        transaction {
            MilestoneSelfAssessmentTable.insert {
                it[milestoneId] = done
                it[studentId] = student
                it[answer] = SelfAssessmentAnswer.PARTLY
                it[comment] = "Test kann die Zahlen noch nicht gut"
                it[createdAt] = OffsetDateTime.now()
            }
        }
        return Roadmap(goal, done, current, next)
    }
}

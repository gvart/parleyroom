package com.gvart.parleyroom.goal

import com.gvart.parleyroom.admin.data.FeatureFlagTable
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.TEACHER
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.RoadmapItemKind
import com.gvart.parleyroom.goal.data.RoadmapItemState
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.RoadmapMilestoneTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserTable
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** Goals and roadmaps seeded directly (the API is covered by the integration tests). */
object GoalFixtures {

    /** Feature flag `roadmap` on (TRUNCATE users CASCADE turns it off again before every test). */
    fun enableRoadmap() = transaction {
        FeatureFlagTable.upsert {
            it[name] = "roadmap"
            it[enabled] = true
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    /** Today for the test students (users.timezone defaults to Europe/Berlin). */
    val today: LocalDate get() = LocalDate.now(ZoneId.of("Europe/Berlin"))

    val TEACHER_2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000005")

    /** A second teacher (teacher2@test.com, password123) without students. */
    fun secondTeacher() = transaction {
        UserTable.insert {
            it[id] = TEACHER_2
            it[email] = "teacher2@test.com"
            it[firstName] = "Other"
            it[lastName] = "Teacher"
            it[passwordHash] = "\$2a\$10\$zikr3./cgtr3mqY8YhpbzOKijoUE4C2StC72a9L9swGo/KCSyhl/e"
            it[role] = UserRole.TEACHER
            it[initials] = "OT"
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }
    }

    fun goal(
        studentId: UUID = STUDENT,
        teacherId: UUID? = TEACHER,
        start: LocalDate = today.minusDays(50),
        deadline: LocalDate = today.plusDays(50),
        status: GoalStatus = GoalStatus.ACTIVE,
        title: String = "Goethe A1 bestehen",
        teacherInput: Boolean = false,
    ): UUID = transaction {
        GoalTable.insertAndGetId {
            it[GoalTable.studentId] = studentId
            it[GoalTable.teacherId] = teacherId
            it[GoalTable.title] = title
            it[startDate] = start.toKotlinLocalDate()
            it[GoalTable.deadline] = deadline.toKotlinLocalDate()
            it[lessonsPerWeek] = BigDecimal("2")
            it[lessonMinutes] = 60
            it[GoalTable.status] = status
            if (teacherInput) {
                it[exam] = "Goethe Start Deutsch 1"
                it[levelNote] = "LEHRER-NOTIZ: schwach im Hören"
                it[interests] = "Schach"
                it[materials] = "Menschen A1"
            }
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }.value
    }

    /** A milestone with one item per entry of [states]. */
    fun milestone(
        goalId: UUID,
        position: Int,
        states: List<RoadmapItemState> = emptyList(),
        weight: Double = 1.0,
        done: Boolean = false,
        title: String = "Etappe $position",
    ): UUID = transaction {
        val now = OffsetDateTime.now()
        val id = RoadmapMilestoneTable.insertAndGetId {
            it[RoadmapMilestoneTable.goalId] = goalId
            it[RoadmapMilestoneTable.position] = position
            it[RoadmapMilestoneTable.title] = title
            it[startDate] = today.toKotlinLocalDate()
            it[endDate] = today.plusDays(7).toKotlinLocalDate()
            it[RoadmapMilestoneTable.weight] = BigDecimal.valueOf(weight)
            it[grammarPoints] = listOf("Perfekt")
            it[wordFields] = listOf("Familie")
            it[examPart] = "Sprechen Teil 1"
            it[teacherTips] = "Geheimer Tipp"
            it[canDo] = "Ich kann $title."
            if (done) it[doneAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        states.forEachIndexed { i, state ->
            RoadmapItemTable.insert {
                it[milestoneId] = id
                it[RoadmapItemTable.position] = i + 1
                it[kind] = RoadmapItemKind.GRAMMAR
                it[RoadmapItemTable.title] = "Punkt ${i + 1}"
                it[RoadmapItemTable.state] = state
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
        id
    }
}

package com.gvart.parleyroom.reminder.service

import com.gvart.parleyroom.admin.data.FeatureFlag
import com.gvart.parleyroom.admin.service.FeatureFlagService
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.transfer.exception.ForbiddenException
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.MilestoneSelfAssessmentTable
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.RoadmapMilestoneTable
import com.gvart.parleyroom.goal.data.SelfAssessmentAnswer
import com.gvart.parleyroom.goal.service.GoalTrackStatus
import com.gvart.parleyroom.goal.service.GoalViews
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lessonprep.data.LessonPlanStatus
import com.gvart.parleyroom.lessonprep.data.LessonPlanTable
import com.gvart.parleyroom.reminder.transfer.AttentionReason
import com.gvart.parleyroom.reminder.transfer.AttentionReasonType
import com.gvart.parleyroom.reminder.transfer.AttentionStudent
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** Which features the attention reasons may use; reasons of a feature that is off are left out. */
data class AttentionFeatures(val roadmap: Boolean, val lessonPrep: Boolean) {
    /** Wrapping up needs the wrap-up flow (lessonPrep) or, with the roadmap only, a goal to check items off on. */
    val wrapUp: Boolean get() = roadmap || lessonPrep
}

/**
 * The teacher's "Needs attention" list, also the weekly digest's content: the teacher's ACTIVE students that are
 * BEHIND on their goal, have an open self-assessment mismatch, a 1:1 lesson of the last [WINDOW] not wrapped up, or
 * no homework from this teacher assigned within [WINDOW]. A fixed number of queries whatever the number of students.
 */
class TeacherAttentionService(private val flags: FeatureFlagService) {

    /** GET /api/v1/teacher/attention, for the principal's own students; students 403. */
    fun attention(principal: UserPrincipal): List<AttentionStudent> {
        if (principal.role == UserRole.STUDENT) throw ForbiddenException("Needs attention is for teachers only")
        val features = features()
        return transaction { compute(principal.id, OffsetDateTime.now(), features) }
    }

    fun features(enabled: Map<String, Boolean> = flags.flags()) =
        AttentionFeatures(roadmap = enabled[FeatureFlag.ROADMAP.key] == true, lessonPrep = enabled[FeatureFlag.LESSON_PREP.key] == true)

    /** Must run in a transaction. Most severe first (by the reasons' order in [AttentionReasonType]), then by name. */
    fun compute(teacherId: UUID, now: OffsetDateTime, features: AttentionFeatures): List<AttentionStudent> {
        val students = TeacherStudentTable.join(UserTable, JoinType.INNER, TeacherStudentTable.studentId, UserTable.id)
            .select(UserTable.id, UserTable.firstName, UserTable.lastName)
            .where {
                (TeacherStudentTable.teacherId eq teacherId) and (TeacherStudentTable.status eq UserStatus.ACTIVE) and
                    (UserTable.status eq UserStatus.ACTIVE)
            }
            .associate { it[UserTable.id].value to "${it[UserTable.firstName]} ${it[UserTable.lastName]}".trim() }
        if (students.isEmpty()) return emptyList()
        val ids = students.keys.toList()
        val since = now.minus(WINDOW)
        val reasons = mutableMapOf<UUID, MutableList<AttentionReason>>()
        fun add(studentId: UUID, reason: AttentionReason) { reasons.getOrPut(studentId) { mutableListOf() } += reason }

        val goals = if (!features.roadmap) emptyList() else GoalTable.selectAll()
            .where { (GoalTable.studentId inList ids) and (GoalTable.teacherId eq teacherId) and (GoalTable.status eq GoalStatus.ACTIVE) }
            .toList()
        if (goals.isNotEmpty()) {
            for (goal in GoalViews.summaries(goals)) {
                if (goal.progress.status != GoalTrackStatus.BEHIND) continue
                add(UUID.fromString(goal.studentId), AttentionReason(
                    AttentionReasonType.BEHIND, goal.id, "${goal.progress.percent ?: 0}/${goal.progress.elapsedPercent}",
                ))
            }
            val studentOfGoal = goals.associate { it[GoalTable.id].value to it[GoalTable.studentId].value }
            (MilestoneSelfAssessmentTable innerJoin RoadmapMilestoneTable)
                .select(
                    RoadmapMilestoneTable.id, RoadmapMilestoneTable.goalId, RoadmapMilestoneTable.title,
                    MilestoneSelfAssessmentTable.studentId, MilestoneSelfAssessmentTable.createdAt,
                )
                .where {
                    (RoadmapMilestoneTable.goalId inList studentOfGoal.keys) and RoadmapMilestoneTable.doneAt.isNotNull() and
                        (MilestoneSelfAssessmentTable.answer neq SelfAssessmentAnswer.YES)
                }
                .filter { it[MilestoneSelfAssessmentTable.studentId].value == studentOfGoal[it[RoadmapMilestoneTable.goalId].value] }
                .forEach {
                    add(it[MilestoneSelfAssessmentTable.studentId].value, AttentionReason(
                        AttentionReasonType.MISMATCH, it[RoadmapMilestoneTable.id].value.toString(),
                        it[RoadmapMilestoneTable.title], it[MilestoneSelfAssessmentTable.createdAt],
                    ))
                }
        }

        if (features.wrapUp) {
            val withGoal = goals.map { it[GoalTable.studentId].value }.toSet()
            val candidates = TeacherLessons.ended(teacherId, ids, since, now)
                .filter { features.lessonPrep || it.studentId in withGoal }
            for (lesson in TeacherLessons.notWrappedUp(candidates)) {
                add(lesson.studentId, AttentionReason(AttentionReasonType.NOT_WRAPPED_UP, lesson.id.toString(), lesson.title, lesson.startsAt))
            }
        }

        val lastAssigned = HomeworkTable.createdAt.max()
        val lastHomework = (HomeworkTable innerJoin AssignmentTable)
            .select(HomeworkTable.studentId, lastAssigned)
            .where { (HomeworkTable.studentId inList ids) and (AssignmentTable.teacherId eq teacherId) }
            .groupBy(HomeworkTable.studentId)
            .associate { it[HomeworkTable.studentId].value to it[lastAssigned] }
        for (id in ids) {
            val last = lastHomework[id]
            if (last == null || last.isBefore(since)) add(id, AttentionReason(AttentionReasonType.NO_HOMEWORK, at = last))
        }

        return reasons.map { (id, list) -> AttentionStudent(id.toString(), students.getValue(id), list.sortedBy { it.type.ordinal }) }
            .sortedWith(compareBy({ it.reasons.first().type.ordinal }, { -it.reasons.size }, { it.studentName }))
    }

    companion object {
        val WINDOW: Duration = Duration.ofDays(14)
    }
}

/** A 1:1 lesson with its confirmed student; [endsAt] is when it ended, else its scheduled end. */
data class TeacherLesson(
    val id: UUID,
    val teacherId: UUID,
    val studentId: UUID,
    val title: String,
    val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime,
)

/** Lesson queries shared by the attention list and the reminders. Must run in a transaction. */
object TeacherLessons {

    private val HELD = listOf(LessonStatus.COMPLETED, LessonStatus.CONFIRMED, LessonStatus.IN_PROGRESS)

    /**
     * 1:1 lessons of [teacherId] (any teacher when null) with one of [studentIds] (any when null) that ended within
     * [from, to]: COMPLETED, or CONFIRMED / IN_PROGRESS past their scheduled end.
     */
    fun ended(teacherId: UUID?, studentIds: List<UUID>?, from: OffsetDateTime, to: OffsetDateTime): List<TeacherLesson> =
        (LessonTable innerJoin LessonStudentTable)
            .select(LESSON_COLUMNS)
            .where {
                (LessonTable.type eq LessonType.ONE_ON_ONE) and (LessonTable.status inList HELD) and
                    (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) and
                    // Starts at most a day before the window; the end is checked below.
                    LessonTable.scheduledAt.between(from.minusDays(1), to) and
                    (if (teacherId != null) LessonTable.teacherId eq teacherId else LessonTable.teacherId.isNotNull()) and
                    (if (studentIds != null) LessonStudentTable.studentId inList studentIds else LessonStudentTable.studentId.isNotNull())
            }
            .map(::toLesson)
            .filter { !it.endsAt.isBefore(from) && !it.endsAt.isAfter(to) }

    /** [lessons] without a DONE plan and without a roadmap item checked in them (two queries). */
    fun notWrappedUp(lessons: List<TeacherLesson>): List<TeacherLesson> {
        if (lessons.isEmpty()) return emptyList()
        val ids = lessons.map { it.id }
        val done = LessonPlanTable.select(LessonPlanTable.lessonId)
            .where { (LessonPlanTable.lessonId inList ids) and (LessonPlanTable.status eq LessonPlanStatus.DONE) }
            .mapNotNull { it[LessonPlanTable.lessonId]?.value }.toSet()
        val checked = RoadmapItemTable.select(RoadmapItemTable.lessonId)
            .where { RoadmapItemTable.lessonId inList ids }
            .mapNotNull { it[RoadmapItemTable.lessonId]?.value }.toSet()
        return lessons.filter { it.id !in done && it.id !in checked }
    }

    /** Lessons of [ids] that have an assignment (homework) from them. */
    fun withHomework(ids: List<UUID>): Set<UUID> =
        if (ids.isEmpty()) emptySet()
        else AssignmentTable.select(AssignmentTable.lessonId).where { AssignmentTable.lessonId inList ids }
            .mapNotNull { it[AssignmentTable.lessonId]?.value }.toSet()

    val LESSON_COLUMNS = listOf(
        LessonTable.id, LessonTable.teacherId, LessonTable.title, LessonTable.scheduledAt, LessonTable.durationMinutes,
        LessonTable.endedAt, LessonStudentTable.studentId,
    )

    /** A row with [LESSON_COLUMNS]. */
    fun toLesson(row: ResultRow): TeacherLesson {
        val startsAt = row[LessonTable.scheduledAt]
        return TeacherLesson(
            id = row[LessonTable.id].value,
            teacherId = row[LessonTable.teacherId]!!.value,
            studentId = row[LessonStudentTable.studentId].value,
            title = row[LessonTable.title],
            startsAt = startsAt,
            endsAt = row[LessonTable.endedAt] ?: startsAt.plusMinutes(row[LessonTable.durationMinutes].toLong()),
        )
    }
}

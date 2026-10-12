package com.gvart.parleyroom.lessonprep.service

import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.goal.data.GoalTable
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.goal.data.RoadmapMilestoneTable
import com.gvart.parleyroom.homework.data.AssignmentTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lessonprep.data.LessonPlanTable
import com.gvart.parleyroom.lessonprep.transfer.HistoryHomework
import com.gvart.parleyroom.lessonprep.transfer.HistoryItem
import com.gvart.parleyroom.lessonprep.transfer.HistoryPlanRef
import com.gvart.parleyroom.lessonprep.transfer.LessonHistoryEntry
import com.gvart.parleyroom.lessonprep.transfer.LessonHistoryResponse
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A student's lesson timeline for the teacher, filtered and paginated in SQL. A page costs a fixed
 * number of queries (count, page, next lesson, plans, roadmap items, homework), whatever its size.
 */
class LessonHistoryService {

    fun history(studentId: UUID, principal: UserPrincipal, page: PageRequest): LessonHistoryResponse = transaction {
        LessonPrepAccess.requireStudent(studentId, principal)
        val teacher = LessonPrepAccess.teacherScope(principal)
        val past = (LessonTable innerJoin LessonStudentTable).select(LessonTable.columns)
            .where { LessonPrepAccess.studentLessons(studentId, teacher) and (LessonTable.scheduledAt lessEq OffsetDateTime.now()) }
        val total = past.count()
        val rows = past.orderBy(LessonTable.scheduledAt to SortOrder.DESC, LessonTable.id to SortOrder.ASC)
            .limit(page.pageSize).offset(page.offset).toList()
        val next = LessonPrepAccess.nextLesson(studentId, teacher, unplannedOnly = false)
        val entries = entries(studentId, listOfNotNull(next) + rows)
        LessonHistoryResponse(
            nextLesson = next?.let { entries.first() },
            items = if (next == null) entries else entries.drop(1),
            total = total,
            page = page.page,
            pageSize = page.pageSize,
        )
    }

    /** Plans, roadmap items and homework of all [lessons] in one query each; only [studentId]'s. */
    private fun entries(studentId: UUID, lessons: List<ResultRow>): List<LessonHistoryEntry> {
        if (lessons.isEmpty()) return emptyList()
        val ids = lessons.map { it[LessonTable.id].value }
        val plans = LessonPlanTable.selectAll()
            .where { (LessonPlanTable.lessonId inList ids) and (LessonPlanTable.studentId eq studentId) }
            .associateBy { it[LessonPlanTable.lessonId]!!.value }
        val items = (RoadmapItemTable innerJoin RoadmapMilestoneTable innerJoin GoalTable)
            .select(RoadmapItemTable.id, RoadmapItemTable.title, RoadmapItemTable.state, RoadmapItemTable.lessonId)
            .where { (RoadmapItemTable.lessonId inList ids) and (GoalTable.studentId eq studentId) }
            .orderBy(RoadmapMilestoneTable.position to SortOrder.ASC, RoadmapItemTable.position to SortOrder.ASC)
            .groupBy { it[RoadmapItemTable.lessonId]!!.value }
        val homework = (HomeworkTable innerJoin AssignmentTable)
            .select(HomeworkTable.id, HomeworkTable.assignmentId, HomeworkTable.status, AssignmentTable.title, AssignmentTable.lessonId)
            .where { (AssignmentTable.lessonId inList ids) and (HomeworkTable.studentId eq studentId) }
            .orderBy(AssignmentTable.createdAt, SortOrder.ASC)
            .groupBy { it[AssignmentTable.lessonId]!!.value }
        return lessons.map { lesson ->
            val id = lesson[LessonTable.id].value
            val plan = plans[id]
            LessonHistoryEntry(
                lessonId = id.toString(),
                title = lesson[LessonTable.title],
                scheduledAt = lesson[LessonTable.scheduledAt],
                status = lesson[LessonTable.status],
                durationMinutes = lesson[LessonTable.durationMinutes],
                rawNotes = lesson[LessonTable.rawNotes],
                plan = plan?.let {
                    HistoryPlanRef(it[LessonPlanTable.id].value.toString(), it[LessonPlanTable.plan]?.lessonGoal, it[LessonPlanTable.status])
                },
                coveredNote = plan?.get(LessonPlanTable.coveredNote),
                roadmapItems = items[id].orEmpty().map {
                    HistoryItem(it[RoadmapItemTable.id].value.toString(), it[RoadmapItemTable.title], it[RoadmapItemTable.state])
                },
                homework = homework[id].orEmpty().map {
                    HistoryHomework(
                        it[HomeworkTable.id].value.toString(), it[HomeworkTable.assignmentId].value.toString(),
                        it[AssignmentTable.title], it[HomeworkTable.status],
                    )
                },
            )
        }
    }
}

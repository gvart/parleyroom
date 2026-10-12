package com.gvart.parleyroom.lessonprep.service

import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.service.GenerationJobs
import com.gvart.parleyroom.goal.data.RoadmapItemTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lessonprep.data.LessonPlanTable
import com.gvart.parleyroom.lessonprep.transfer.LessonPlanResponse
import com.gvart.parleyroom.lessonprep.transfer.PlanLessonRef
import com.gvart.parleyroom.lessonprep.transfer.PlanRoadmapItem
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Builds lesson plan responses. Must run in a transaction. */
object LessonPlanViews {

    fun plan(row: ResultRow): LessonPlanResponse {
        val planId = row[LessonPlanTable.id].value
        val content = row[LessonPlanTable.plan]
        val lesson = row[LessonPlanTable.lessonId]?.value?.let { id -> LessonTable.selectAll().where { LessonTable.id eq id }.singleOrNull() }
        val itemIds = content?.roadmapItemIds.orEmpty().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        val items = if (itemIds.isEmpty()) emptyMap() else RoadmapItemTable.selectAll()
            .where { RoadmapItemTable.id inList itemIds }
            .associateBy { it[RoadmapItemTable.id].value }
        val job = GenerationJobTable.selectAll()
            .where { GenerationJobTable.lessonPlanId eq planId }
            .orderBy(GenerationJobTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
        return LessonPlanResponse(
            id = planId.toString(),
            studentId = row[LessonPlanTable.studentId].value.toString(),
            teacherId = row[LessonPlanTable.teacherId].value.toString(),
            goalId = row[LessonPlanTable.goalId]?.value?.toString(),
            lesson = lesson?.let(::lessonRef),
            prepNote = row[LessonPlanTable.prepNote],
            explainInRussian = row[LessonPlanTable.explainInRussian],
            plan = content,
            roadmapItems = itemIds.mapNotNull(items::get).map {
                PlanRoadmapItem(it[RoadmapItemTable.id].value.toString(), it[RoadmapItemTable.title], it[RoadmapItemTable.kind], it[RoadmapItemTable.state])
            },
            coveredNote = row[LessonPlanTable.coveredNote],
            status = row[LessonPlanTable.status],
            job = job?.let(GenerationJobs::toResponse),
            createdAt = row[LessonPlanTable.createdAt],
            updatedAt = row[LessonPlanTable.updatedAt],
        )
    }

    fun lessonRef(lesson: ResultRow) = PlanLessonRef(
        id = lesson[LessonTable.id].value.toString(),
        title = lesson[LessonTable.title],
        scheduledAt = lesson[LessonTable.scheduledAt],
        durationMinutes = lesson[LessonTable.durationMinutes],
        status = lesson[LessonTable.status],
    )
}

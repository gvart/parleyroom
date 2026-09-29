package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.data.DraftBundleTable
import com.gvart.parleyroom.ai.data.DraftScope
import com.gvart.parleyroom.ai.data.GenerationJobKind
import com.gvart.parleyroom.ai.data.GenerationJobStatus
import com.gvart.parleyroom.ai.data.GenerationJobTable
import com.gvart.parleyroom.ai.transfer.AiJobSummary
import com.gvart.parleyroom.ai.transfer.AiJobTargetType
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.group.data.GroupTable
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** The teacher's AI task tray: jobs with the names of what they work on. */
object AiJobSummaries {

    val RECENT: Duration = Duration.ofHours(24)
    private val ACTIVE = listOf(GenerationJobStatus.QUEUED, GenerationJobStatus.RUNNING)

    /** Active jobs, plus (unless [activeOnly]) those finished within [RECENT], newest first. */
    fun list(teacherId: UUID, activeOnly: Boolean, limit: Int): List<AiJobSummary> = transaction {
        val since = OffsetDateTime.now().minus(RECENT)
        val rows = GenerationJobTable.selectAll()
            .where {
                val active = GenerationJobTable.status inList ACTIVE
                (GenerationJobTable.teacherId eq teacherId) and
                        (if (activeOnly) active else active or (GenerationJobTable.finishedAt greaterEq since))
            }
            .orderBy(GenerationJobTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .toList()
        summarize(rows)
    }

    /** One job's summary with its teacher, or null if it is gone. Must run in a transaction. */
    fun find(jobId: UUID): Pair<UUID, AiJobSummary>? {
        val row = GenerationJobTable.selectAll().where { GenerationJobTable.id eq jobId }.singleOrNull() ?: return null
        return row[GenerationJobTable.teacherId].value to summarize(listOf(row)).single()
    }

    private fun summarize(rows: List<ResultRow>): List<AiJobSummary> {
        val bundles = rowsById(DraftBundleTable, rows.mapNotNull { it[GenerationJobTable.bundleId]?.value })
        val lessonIds = rows.mapNotNull { it[GenerationJobTable.lessonId]?.value } +
                bundles.values.mapNotNull { it[DraftBundleTable.lessonId]?.value }
        val lessons = rowsById(LessonTable, lessonIds)
        val groups = names(GroupTable, GroupTable.name, lessons.values.mapNotNull { it[LessonTable.groupId]?.value })
        val documents = names(DocumentTable, DocumentTable.title, rows.mapNotNull { it[GenerationJobTable.documentId]?.value })
        val materials = names(MaterialTable, MaterialTable.name, rows.mapNotNull { it[GenerationJobTable.materialId]?.value })
        // A 1:1 lesson is named after its (single) student.
        val oneOnOne = lessons.values.filter { it[LessonTable.type] == LessonType.ONE_ON_ONE }.map { it[LessonTable.id].value }
        val lessonStudent = if (oneOnOne.isEmpty()) emptyMap() else LessonStudentTable
            .select(LessonStudentTable.lessonId, LessonStudentTable.studentId)
            .where { LessonStudentTable.lessonId inList oneOnOne }
            .associate { it[LessonStudentTable.lessonId].value to it[LessonStudentTable.studentId].value }
        val studentIds = bundles.values.mapNotNull { it[DraftBundleTable.studentId]?.value } + lessonStudent.values
        val students = if (studentIds.isEmpty()) emptyMap() else UserTable
            .select(UserTable.id, UserTable.firstName, UserTable.lastName)
            .where { UserTable.id inList studentIds.distinct() }
            .associate { it[UserTable.id].value to "${it[UserTable.firstName]} ${it[UserTable.lastName]}".trim() }

        return rows.map { row ->
            val kind = row[GenerationJobTable.kind]
            val bundle = row[GenerationJobTable.bundleId]?.value?.let(bundles::get)
            val lessonId = bundle?.get(DraftBundleTable.lessonId)?.value ?: row[GenerationJobTable.lessonId]?.value
            val lesson = lessonId?.let(lessons::get)
            val studentId = bundle?.get(DraftBundleTable.studentId)?.value ?: lessonId?.let(lessonStudent::get)
            // A club GENERATE also stores the notes document it created: the draft is still the target.
            val documentId = row[GenerationJobTable.documentId]?.value.takeIf { bundle == null }
            val input = GenerationJobs.input(row)
            AiJobSummary(
                id = row[GenerationJobTable.id].value.toString(),
                kind = kind,
                status = row[GenerationJobTable.status],
                targetType = when {
                    bundle != null -> if (bundle[DraftBundleTable.scope] == DraftScope.STUDENT) AiJobTargetType.STUDENT else AiJobTargetType.LESSON
                    documentId != null -> AiJobTargetType.DOCUMENT
                    row[GenerationJobTable.materialId] != null -> AiJobTargetType.MATERIAL
                    kind == GenerationJobKind.FILL_TRANSLATIONS -> AiJobTargetType.VOCAB
                    else -> AiJobTargetType.LESSON
                },
                bundleId = bundle?.get(DraftBundleTable.id)?.value?.toString(),
                lessonId = lessonId?.toString(),
                lessonTitle = lesson?.get(LessonTable.title),
                studentId = studentId?.toString(),
                studentName = studentId?.let(students::get),
                groupName = lesson?.get(LessonTable.groupId)?.value?.let(groups::get),
                documentId = documentId?.toString(),
                documentTitle = documentId?.let(documents::get),
                materialId = row[GenerationJobTable.materialId]?.value?.toString(),
                materialName = row[GenerationJobTable.materialId]?.value?.let(materials::get),
                entryCount = input.entryIds?.size,
                draftKinds = input.kinds.takeIf { bundle != null },
                itemRefine = input.itemId != null,
                errorCode = row[GenerationJobTable.errorCode],
                createdAt = row[GenerationJobTable.createdAt],
                startedAt = row[GenerationJobTable.startedAt],
                finishedAt = row[GenerationJobTable.finishedAt],
            )
        }
    }

    private fun rowsById(table: UUIDTable, ids: List<UUID>): Map<UUID, ResultRow> =
        if (ids.isEmpty()) emptyMap()
        else table.selectAll().where { table.id inList ids.distinct() }.associateBy { it[table.id].value }

    private fun names(
        table: UUIDTable,
        column: Column<String>,
        ids: List<UUID>,
    ): Map<UUID, String> =
        if (ids.isEmpty()) emptyMap()
        else table.select(table.id, column).where { table.id inList ids.distinct() }.associate { it[table.id].value to it[column] }
}

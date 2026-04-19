package com.gvart.parleyroom.material.service

import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.material.data.FolderShareTable
import com.gvart.parleyroom.material.data.LessonMaterialTable
import com.gvart.parleyroom.material.data.MaterialFolderTable
import com.gvart.parleyroom.material.data.MaterialShareTable
import com.gvart.parleyroom.material.data.MaterialTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Single source of truth for material & folder access. Used by listing,
 * downloading, lesson-attachment reads, and share-write authorization.
 *
 * Access rules
 * ------------
 * Teacher: owns every folder/material with matching teacher_id.
 * Student: has access to material M iff ANY of
 *   (1) (M, student) in material_shares,
 *   (2) M.folder_id is in the shared-folder closure for this student,
 *   (3) M is attached (via lesson_materials) to a lesson the student is CONFIRMED on.
 * Admin: universal read.
 *
 * Folder access for students: a folder F is visible iff F is directly shared OR F
 * is a descendant of a directly-shared folder.
 */
class MaterialAccessResolver {

    /** Caller must already be inside an Exposed transaction. */
    fun accessibleFolderIdsForStudent(studentId: UUID): Set<UUID> {
        val directlyShared = FolderShareTable
            .select(FolderShareTable.folderId)
            .where { FolderShareTable.studentId eq studentId }
            .map { it[FolderShareTable.folderId].value }
            .toSet()

        if (directlyShared.isEmpty()) return emptySet()

        // Build the descendant closure. We pull every folder owned by the relevant
        // teachers (folder owners of the shared roots) and walk in memory.
        val ownerIds = MaterialFolderTable
            .select(MaterialFolderTable.teacherId)
            .where { MaterialFolderTable.id inList directlyShared }
            .map { it[MaterialFolderTable.teacherId].value }
            .toSet()

        val adjacency = mutableMapOf<UUID, MutableList<UUID>>()
        MaterialFolderTable
            .selectAll()
            .where { MaterialFolderTable.teacherId inList ownerIds }
            .forEach { row ->
                val id = row[MaterialFolderTable.id].value
                val parent = row[MaterialFolderTable.parentFolderId]?.value
                if (parent != null) {
                    adjacency.getOrPut(parent) { mutableListOf() }.add(id)
                }
            }

        val visible = HashSet<UUID>(directlyShared)
        val queue = ArrayDeque(directlyShared)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            adjacency[current]?.forEach { child ->
                if (visible.add(child)) queue.addLast(child)
            }
        }
        return visible
    }

    /** Caller must already be inside an Exposed transaction. */
    fun accessibleLessonIdsForStudent(studentId: UUID): Set<UUID> =
        LessonStudentTable
            .select(LessonStudentTable.lessonId)
            .where {
                (LessonStudentTable.studentId eq studentId) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .map { it[LessonStudentTable.lessonId].value }
            .toSet()

    /** Caller must already be inside an Exposed transaction. */
    fun accessibleMaterialIdsForStudent(studentId: UUID): Set<UUID> {
        val result = HashSet<UUID>()

        MaterialShareTable
            .select(MaterialShareTable.materialId)
            .where { MaterialShareTable.studentId eq studentId }
            .forEach { result.add(it[MaterialShareTable.materialId].value) }

        val folderIds = accessibleFolderIdsForStudent(studentId)
        if (folderIds.isNotEmpty()) {
            MaterialTable
                .select(MaterialTable.id)
                .where { MaterialTable.folderId inList folderIds }
                .forEach { result.add(it[MaterialTable.id].value) }
        }

        val lessonIds = accessibleLessonIdsForStudent(studentId)
        if (lessonIds.isNotEmpty()) {
            LessonMaterialTable
                .select(LessonMaterialTable.materialId)
                .where { LessonMaterialTable.lessonId inList lessonIds }
                .forEach { result.add(it[LessonMaterialTable.materialId].value) }
        }

        return result
    }

    /** Caller must already be inside an Exposed transaction. */
    fun canStudentView(materialId: UUID, studentId: UUID): Boolean {
        val direct = MaterialShareTable.selectAll()
            .where {
                (MaterialShareTable.materialId eq materialId) and
                        (MaterialShareTable.studentId eq studentId)
            }
            .empty().not()
        if (direct) return true

        val folderId = MaterialTable
            .select(MaterialTable.folderId)
            .where { MaterialTable.id eq materialId }
            .firstOrNull()
            ?.get(MaterialTable.folderId)
            ?.value

        if (folderId != null) {
            val visibleFolders = accessibleFolderIdsForStudent(studentId)
            if (folderId in visibleFolders) return true
        }

        val viaLesson = LessonMaterialTable
            .join(
                LessonStudentTable,
                JoinType.INNER,
                onColumn = LessonMaterialTable.lessonId,
                otherColumn = LessonStudentTable.lessonId,
            )
            .select(LessonMaterialTable.materialId)
            .where {
                (LessonMaterialTable.materialId eq materialId) and
                        (LessonStudentTable.studentId eq studentId) and
                        (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED)
            }
            .empty().not()

        return viaLesson
    }

    /** Caller must already be inside an Exposed transaction. */
    fun requireFolderOwnership(folderRow: ResultRow, principal: UserPrincipal) {
        if (principal.role == UserRole.ADMIN) return
        if (principal.role != UserRole.TEACHER ||
            folderRow[MaterialFolderTable.teacherId].value != principal.id
        ) {
            throw com.gvart.parleyroom.common.transfer.exception.ForbiddenException(
                "Only the owning teacher can modify this folder"
            )
        }
    }
}

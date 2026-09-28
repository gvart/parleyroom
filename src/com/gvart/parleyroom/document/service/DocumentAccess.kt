package com.gvart.parleyroom.document.service

import com.gvart.parleyroom.document.data.DocumentGroupTable
import com.gvart.parleyroom.document.data.DocumentLessonTable
import com.gvart.parleyroom.document.data.DocumentStudentTable
import com.gvart.parleyroom.document.data.DocumentTable
import com.gvart.parleyroom.group.data.GroupMemberTable
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.json.contains
import java.util.UUID

/** Student read access to documents, also used by the material access check. Must be called inside a transaction. */
object DocumentAccess {

    /** Shared with the student directly, via one of their groups, or linked to a lesson they are confirmed on. */
    fun readableByStudent(studentId: UUID): Set<UUID> {
        val direct = DocumentStudentTable.select(DocumentStudentTable.documentId)
            .where { DocumentStudentTable.studentId eq studentId }
            .map { it[DocumentStudentTable.documentId].value }
        val groupIds = GroupMemberTable.select(GroupMemberTable.groupId)
            .where { GroupMemberTable.studentId eq studentId }
            .map { it[GroupMemberTable.groupId].value }
        val viaGroups = if (groupIds.isEmpty()) emptyList() else DocumentGroupTable.select(DocumentGroupTable.documentId)
            .where { DocumentGroupTable.groupId inList groupIds }
            .map { it[DocumentGroupTable.documentId].value }
        val lessonIds = LessonStudentTable.select(LessonStudentTable.lessonId)
            .where { (LessonStudentTable.studentId eq studentId) and (LessonStudentTable.status eq LessonStudentStatus.CONFIRMED) }
            .map { it[LessonStudentTable.lessonId].value }
        val viaLessons = if (lessonIds.isEmpty()) emptyList() else DocumentLessonTable.select(DocumentLessonTable.documentId)
            .where { DocumentLessonTable.lessonId inList lessonIds }
            .map { it[DocumentLessonTable.documentId].value }
        return (direct + viaGroups + viaLessons).toSet()
    }

    /** True when a document the student can read has a media block referencing the material (checked at access time). */
    fun studentReadsDocumentWithMaterial(studentId: UUID, materialId: UUID): Boolean {
        val readable = readableByStudent(studentId)
        if (readable.isEmpty()) return false
        return DocumentTable.select(DocumentTable.id)
            .where {
                (DocumentTable.id inList readable) and
                        DocumentTable.blocks.contains("""[{"type":"media","materialId":"$materialId"}]""")
            }
            .empty().not()
    }
}

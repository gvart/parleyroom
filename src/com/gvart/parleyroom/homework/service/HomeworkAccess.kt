package com.gvart.parleyroom.homework.service

import com.gvart.parleyroom.homework.data.AssignmentItemTable
import com.gvart.parleyroom.homework.data.HomeworkTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.json.contains
import java.util.UUID

/** Material access granted through homework, used by the material access check. Must be called inside a transaction. */
object HomeworkAccess {

    /** True when a MATERIAL item or a media block of a document snapshot in the student's homework references the material. */
    fun studentHasMaterialInHomework(studentId: UUID, materialId: UUID): Boolean =
        AssignmentItemTable.select(AssignmentItemTable.id)
            .where {
                (AssignmentItemTable.assignmentId inSubQuery HomeworkTable.select(HomeworkTable.assignmentId)
                    .where { HomeworkTable.studentId eq studentId }) and
                        ((AssignmentItemTable.materialId eq materialId) or
                                AssignmentItemTable.blocks.contains("""[{"type":"media","materialId":"$materialId"}]"""))
            }
            .empty().not()
}

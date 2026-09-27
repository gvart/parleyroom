package com.gvart.parleyroom.activity.service

import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.data.LearningActivityTable
import org.jetbrains.exposed.sql.insert
import java.time.OffsetDateTime
import java.util.UUID

object LearningActivityRecorder {

    /** Must be called inside the caller's transaction so the log commits or rolls back with the action. */
    fun record(userId: UUID, kind: ActivityKind, refId: UUID?) {
        LearningActivityTable.insert {
            it[LearningActivityTable.userId] = userId
            it[LearningActivityTable.kind] = kind
            it[LearningActivityTable.refId] = refId
            it[occurredAt] = OffsetDateTime.now()
        }
    }
}

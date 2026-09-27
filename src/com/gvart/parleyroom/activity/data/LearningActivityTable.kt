package com.gvart.parleyroom.activity.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

enum class ActivityKind { VOCAB_REVIEW, LESSON_COMPLETED, HOMEWORK_SUBMITTED }

object LearningActivityTable : UUIDTable("learning_activity") {
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val kind = enumerationByName<ActivityKind>("kind", 32)
    val refId = javaUUID("ref_id").nullable()
    val occurredAt = timestampWithTimeZone("occurred_at")
}

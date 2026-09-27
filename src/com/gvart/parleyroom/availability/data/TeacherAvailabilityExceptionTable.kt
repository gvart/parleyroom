package com.gvart.parleyroom.availability.data

import com.gvart.parleyroom.common.data.pgEnum
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone

object TeacherAvailabilityExceptionTable : UUIDTable("teacher_availability_exception") {
    val teacherId = reference("teacher_id", UserTable)
    val type = pgEnum<AvailabilityExceptionType>("type", "AVAILABILITY_EXCEPTION_TYPE")
    val startAt = timestampWithTimeZone("start_at")
    val endAt = timestampWithTimeZone("end_at")
    val reason = text("reason").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

package com.gvart.parleyroom.availability.data

import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.time
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone

object TeacherWeeklyAvailabilityTable : UUIDTable("teacher_weekly_availability") {
    val teacherId = reference("teacher_id", UserTable)
    val dayOfWeek = short("day_of_week")
    val startTime = time("start_time")
    val endTime = time("end_time")
    val createdAt = timestampWithTimeZone("created_at")
}

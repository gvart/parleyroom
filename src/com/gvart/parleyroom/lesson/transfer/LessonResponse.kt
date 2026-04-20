package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.lesson.data.LessonStatus
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class LessonResponse(
    val id: String,
    val title: String,
    val type: LessonType,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val scheduledAt: OffsetDateTime,
    val durationMinutes: Int,
    val teacherId: String,
    val status: LessonStatus,
    val topic: String,
    val level: LanguageLevel? = null,
    val maxParticipants: Int? = null,
    val students: List<LessonStudentResponse> = emptyList(),
    @Serializable(with = OffsetDateTimeSerializer::class)
    val startedAt: OffsetDateTime? = null,
    val pendingReschedule: PendingRescheduleResponse? = null,
    val sharedDocument: String? = null,
    val teacherNotes: String? = null,
    val studentNotes: String? = null,
    val teacherWentWell: String? = null,
    val teacherWorkingOn: String? = null,
    val studentReflection: String? = null,
    val studentHardToday: String? = null,
    val createdBy: String,
    val updatedBy: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

@Serializable
data class PendingRescheduleResponse(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val newScheduledAt: OffsetDateTime,
    val note: String? = null,
    val requestedBy: String,
)

@Serializable
data class LessonStudentResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val status: String,
)

package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.lesson.data.LessonStatus
import kotlinx.serialization.Serializable

@Serializable
data class LessonResponse(
    val id: String,
    val title: String,
    val type: LessonType,
    val scheduledAt: String,
    val durationMinutes: Int,
    val teacherId: String,
    val status: LessonStatus,
    val topic: String,
    val level: LanguageLevel? = null,
    val maxParticipants: Int? = null,
    val students: List<LessonStudentResponse> = emptyList(),
    val startedAt: String? = null,
    val pendingReschedule: PendingRescheduleResponse? = null,
    val teacherNotes: String? = null,
    val studentNotes: String? = null,
    val teacherWentWell: String? = null,
    val teacherWorkingOn: String? = null,
    val studentReflection: String? = null,
    val studentHardToday: String? = null,
    val createdBy: String,
    val updatedBy: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class PendingRescheduleResponse(
    val newScheduledAt: String,
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

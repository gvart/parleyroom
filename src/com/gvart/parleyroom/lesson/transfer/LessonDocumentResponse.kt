package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class LessonDocumentResponse(
    val id: String,
    val lessonId: String,
    val teacherNotes: String? = null,
    val studentNotes: String? = null,
    val teacherWentWell: String? = null,
    val teacherWorkingOn: String? = null,
    val studentReflection: String? = null,
    val studentHardToday: String? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
)

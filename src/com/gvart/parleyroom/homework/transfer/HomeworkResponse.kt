package com.gvart.parleyroom.homework.transfer

import com.gvart.parleyroom.homework.data.AttachmentType
import com.gvart.parleyroom.homework.data.HomeworkCategory
import com.gvart.parleyroom.homework.data.HomeworkStatus
import kotlinx.serialization.Serializable

@Serializable
data class HomeworkResponse(
    val id: String,
    val lessonId: String? = null,
    val studentId: String,
    val teacherId: String,
    val title: String,
    val description: String? = null,
    val category: HomeworkCategory,
    val dueDate: String? = null,
    val status: HomeworkStatus,
    val submissionText: String? = null,
    val submissionUrl: String? = null,
    val teacherFeedback: String? = null,
    val attachmentType: AttachmentType? = null,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

package com.gvart.parleyroom.homework.transfer

import com.gvart.parleyroom.homework.data.AttachmentType
import com.gvart.parleyroom.homework.data.HomeworkCategory
import io.ktor.server.plugins.requestvalidation.ValidationResult
import kotlinx.serialization.Serializable

@Serializable
data class CreateHomeworkRequest(
    val lessonId: String? = null,
    val studentId: String,
    val title: String,
    val description: String? = null,
    val category: HomeworkCategory,
    val dueDate: String? = null,
    val attachmentType: AttachmentType? = null,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
) {
    fun validate(): ValidationResult {
        val errors = buildList {
            if (studentId.isBlank()) add("Student ID can't be empty")
            if (title.isBlank()) add("Title can't be empty")
        }
        return if (errors.isNotEmpty()) ValidationResult.Invalid(errors)
        else ValidationResult.Valid
    }
}

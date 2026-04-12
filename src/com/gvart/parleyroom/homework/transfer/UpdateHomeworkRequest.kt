package com.gvart.parleyroom.homework.transfer

import com.gvart.parleyroom.homework.data.HomeworkCategory
import kotlinx.serialization.Serializable

@Serializable
data class UpdateHomeworkRequest(
    val title: String? = null,
    val description: String? = null,
    val category: HomeworkCategory? = null,
    val dueDate: String? = null,
)

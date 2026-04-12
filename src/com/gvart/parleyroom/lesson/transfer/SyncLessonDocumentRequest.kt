package com.gvart.parleyroom.lesson.transfer

import kotlinx.serialization.Serializable

@Serializable
data class SyncLessonDocumentRequest(
    val notes: String? = null,
)
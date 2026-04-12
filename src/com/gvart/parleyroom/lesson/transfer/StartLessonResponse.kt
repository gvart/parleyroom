package com.gvart.parleyroom.lesson.transfer

import com.gvart.parleyroom.video.transfer.VideoAccess
import kotlinx.serialization.Serializable

@Serializable
data class StartLessonResponse(
    val document: LessonDocumentResponse,
    val videoRoom: VideoAccess,
)

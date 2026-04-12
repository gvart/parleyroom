package com.gvart.parleyroom.homework.transfer

import kotlinx.serialization.Serializable

@Serializable
data class SubmitHomeworkRequest(
    val submissionText: String? = null,
    val submissionUrl: String? = null,
)

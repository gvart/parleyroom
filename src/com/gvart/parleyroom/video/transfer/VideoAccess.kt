package com.gvart.parleyroom.video.transfer

import kotlinx.serialization.Serializable

@Serializable
data class VideoAccess(
    val roomName: String,
    val accessToken: String,
    val url: String,
)

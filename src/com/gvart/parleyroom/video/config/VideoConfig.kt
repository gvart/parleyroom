package com.gvart.parleyroom.video.config

import kotlin.time.Duration

data class VideoConfig(
    val url: String,
    val apiKey: String,
    val apiSecret: String,
    val tokenTtl: Duration,
)

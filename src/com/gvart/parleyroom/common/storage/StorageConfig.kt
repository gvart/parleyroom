package com.gvart.parleyroom.common.storage

import kotlin.time.Duration

data class StorageConfig(
    val endpoint: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val uploadUrlTtl: Duration,
    val downloadUrlTtl: Duration,
    val pathStyleAccess: Boolean,
)

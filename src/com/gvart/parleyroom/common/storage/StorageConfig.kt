package com.gvart.parleyroom.common.storage

data class StorageConfig(
    val endpoint: String,
    val region: String,
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val maxFileSize: Long,
    val pathStyleAccess: Boolean,
)

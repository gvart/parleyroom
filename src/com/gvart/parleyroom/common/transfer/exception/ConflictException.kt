package com.gvart.parleyroom.common.transfer.exception

import com.gvart.parleyroom.common.transfer.TagUsage

class ConflictException(
    message: String,
    val code: String? = null,
    val currentRevision: Int? = null,
    val usage: TagUsage? = null,
) : Exception(message)

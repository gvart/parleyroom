package com.gvart.parleyroom.common.transfer.exception

class ConflictException(message: String, val code: String? = null, val currentRevision: Int? = null) : Exception(message)

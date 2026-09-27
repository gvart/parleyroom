package com.gvart.parleyroom.common.transfer.exception

class ForbiddenException(message: String, val code: String? = null) : Exception(message)

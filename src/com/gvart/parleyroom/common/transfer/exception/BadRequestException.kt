package com.gvart.parleyroom.common.transfer.exception

class BadRequestException(message: String, val code: String? = null, val pointer: String? = null) : Exception(message)

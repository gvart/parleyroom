package com.gvart.parleyroom.common.transfer.exception

class TooManyRequestsException(message: String, val code: String? = null) : Exception(message)

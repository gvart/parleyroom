package com.gvart.parleyroom.common.transfer.exception

class ServiceUnavailableException(message: String, val code: String? = null) : Exception(message)

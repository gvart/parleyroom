package com.gvart.parleyroom.common.transfer.exception

import java.time.OffsetDateTime

class TooManyRequestsException(
    message: String,
    val code: String? = null,
    /** When a daily limit lifts again, for limits that reset at a known time. */
    val resetsAt: OffsetDateTime? = null,
) : Exception(message)

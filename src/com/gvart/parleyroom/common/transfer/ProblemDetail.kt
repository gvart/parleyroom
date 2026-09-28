package com.gvart.parleyroom.common.transfer

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class ProblemDetail(
    val type: String = "about:blank",
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    /** JSON pointer of the offending value in the request body, when known. */
    val pointer: String? = null,
) {
    companion object {
        fun of(httpStatus: HttpStatusCode, detail: String? = null, code: String? = null, pointer: String? = null): ProblemDetail {
            return ProblemDetail(
                title = httpStatus.description,
                status = httpStatus.value,
                detail = detail,
                code = code,
                pointer = pointer,
            )
        }
    }
}

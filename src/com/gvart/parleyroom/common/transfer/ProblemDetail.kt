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
) {
    companion object {
        fun of(httpStatus: HttpStatusCode, detail: String? = null, code: String? = null): ProblemDetail {
            return ProblemDetail(
                title = httpStatus.description,
                status = httpStatus.value,
                detail = detail,
                code = code,
            )
        }
    }
}

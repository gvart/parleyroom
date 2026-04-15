package com.gvart.parleyroom.common.transfer

import io.ktor.server.application.ApplicationCall

data class PageRequest(val page: Int, val pageSize: Int) {
    val offset: Long get() = ((page - 1).toLong()) * pageSize

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100

        fun from(call: ApplicationCall): PageRequest {
            val params = call.request.queryParameters
            val page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val pageSize = (params["pageSize"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE)
                .coerceIn(1, MAX_PAGE_SIZE)
            return PageRequest(page, pageSize)
        }
    }
}

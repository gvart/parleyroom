package com.gvart.parleyroom.config

import com.gvart.parleyroom.common.storage.StorageService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureHealthRouting() {
    val storage: StorageService by dependencies

    routing {
        get("/healthz") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }
        get("/readyz") {
            val db = runCatching { transaction { exec("SELECT 1") } }.isSuccess
            val s3 = runCatching { storage.healthCheck() }.isSuccess
            val status = if (db && s3) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, mapOf("db" to db, "s3" to s3))
        }
    }
}
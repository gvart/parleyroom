package com.gvart.parleyroom.progress.routing

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.getQueryUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.progress.service.ProgressService
import com.gvart.parleyroom.progress.transfer.GrammarOverrideRequest
import com.gvart.parleyroom.progress.transfer.GrammarProgressItem
import com.gvart.parleyroom.progress.transfer.StudentProgress
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureProgressRouting() {
    val progressService: ProgressService by dependencies

    routing {
        authenticate {
            route("/api/v1/students/{studentId}/progress") {
                get {
                    val level = call.request.queryParameters["level"]?.let { raw ->
                        LanguageLevel.entries.firstOrNull { it.name == raw } ?: throw BadRequestException("Unknown level: $raw")
                    }
                    val result = progressService.progress(
                        studentId = call.getPathUUID("studentId"),
                        principal = call.requirePrincipal(),
                        teacherId = call.getQueryUUID("teacherId"),
                        level = level,
                        includeLower = call.request.queryParameters["includeLower"] == "true",
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Student progress"
                    description = "Grammar checklist with derived / override / effective status and evidence, topics covered vs. not, " +
                            "summary numbers. Linked teacher, the student (read-only, override notes hidden) or admin."
                    parameters {
                        path("studentId") { description = "Student UUID" }
                        query("teacherId") { description = "Students/admins: whose library (default: the earliest teacher)"; required = false }
                        query("level") { description = "Checklist level (default: the student's level)"; required = false }
                        query("includeLower") { description = "true = also every lower level"; required = false }
                    }
                    responses {
                        HttpStatusCode.OK { schema = jsonSchema<StudentProgress>() }
                        HttpStatusCode.Forbidden { schema = jsonSchema<ProblemDetail>() }
                        HttpStatusCode.NotFound { description = "TEACHER_STUDENT_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/grammar/{grammarTopicId}/override") {
                    put<GrammarOverrideRequest> {
                        val result = progressService.setOverride(
                            call.getPathUUID("studentId"), call.getPathUUID("grammarTopicId"), it, call.requirePrincipal(),
                        )
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Set grammar status override"
                        description = "The teacher's manual status for this student and grammar topic; wins over the derived status. Teacher only."
                        requestBody { schema = jsonSchema<GrammarOverrideRequest>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<GrammarProgressItem>() }
                            HttpStatusCode.BadRequest { description = "GRAMMAR_OVERRIDE_INVALID"; schema = jsonSchema<ProblemDetail>() }
                            HttpStatusCode.NotFound { description = "GRAMMAR_TOPIC_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        val result = progressService.deleteOverride(
                            call.getPathUUID("studentId"), call.getPathUUID("grammarTopicId"), call.requirePrincipal(),
                        )
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Remove grammar status override"
                        description = "Back to the derived status. No-op without an override. Teacher only."
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<GrammarProgressItem>() }
                            HttpStatusCode.NotFound { description = "GRAMMAR_TOPIC_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }
                }
            }
        }
    }
}

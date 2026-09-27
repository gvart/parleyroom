package com.gvart.parleyroom.vocabulary.routing

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.routing.getPathUUID
import com.gvart.parleyroom.common.routing.getQueryUUID
import com.gvart.parleyroom.common.routing.requirePrincipal
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.service.VocabEntryService
import com.gvart.parleyroom.vocabulary.service.VocabSettingsService
import com.gvart.parleyroom.vocabulary.service.VocabularyService
import com.gvart.parleyroom.vocabulary.transfer.AssignVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.AssignVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.QuickAddVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.SetStudentLevelRequest
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabPageResponse
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabResponse
import com.gvart.parleyroom.vocabulary.transfer.UpdateStudentVocabRequest
import com.gvart.parleyroom.vocabulary.transfer.VocabDisplaySetting
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryInput
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryPageResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabEntryResponse
import com.gvart.parleyroom.vocabulary.transfer.VocabSettingsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.jsonSchema
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.configureVocabularyRouting() {
    val vocabularyService: VocabularyService by dependencies
    val vocabEntryService: VocabEntryService by dependencies
    val vocabSettingsService: VocabSettingsService by dependencies

    routing {
        authenticate {
            route("/api/v1/vocabulary") {
                get {
                    val principal = call.requirePrincipal()
                    val params = call.request.queryParameters
                    val filters = VocabularyService.Filters(
                        studentId = call.getQueryUUID("studentId"),
                        status = params["status"]?.let(StudentVocabStatus::valueOf),
                        topicId = call.getQueryUUID("topicId"),
                        level = params["level"]?.let(LanguageLevel::valueOf),
                        lessonId = call.getQueryUUID("lessonId"),
                        q = params["q"],
                    )
                    call.respond(HttpStatusCode.OK, vocabularyService.getWords(principal, filters, PageRequest.from(call)))
                }.describe {
                    summary = "List student vocabulary"
                    description = "Students see their own words, teachers their students', admins all. " +
                            "For students, translations/explanationDe are limited to the vocab display setting " +
                            "(lesson override > teacher–student setting > level default); hidden translations are in " +
                            "revealTranslations when the toggle is allowed."
                    parameters {
                        query("studentId") { description = "Filter by student UUID"; required = false }
                        query("status") { description = "NEW, LEARNING, REVIEW, LEARNED"; required = false }
                        query("topicId") { description = "Filter by topic UUID"; required = false }
                        query("level") { description = "Filter by entry level (A1..C2)"; required = false }
                        query("lessonId") { description = "Words the student received in this lesson"; required = false }
                        query("q") { description = "Lemma substring search"; required = false }
                        query("page") { description = "Page number (1-based, default 1)"; required = false }
                        query("pageSize") { description = "Items per page (default 20, max 100)"; required = false }
                    }
                    responses { HttpStatusCode.OK { schema = jsonSchema<StudentVocabPageResponse>() } }
                }

                post<QuickAddVocabRequest> {
                    call.respond(HttpStatusCode.Created, vocabEntryService.quickAdd(it, call.requirePrincipal()))
                }.describe {
                    summary = "Quick-add a word"
                    description = "Teacher only. Finds the entry in the teacher's library by dedupe key (lemma, article, " +
                            "wordType) or creates it, links it to the lesson if lessonId is given, and assigns it to " +
                            "studentIds + group members (or, if none given, the lesson's confirmed students)."
                    requestBody { schema = jsonSchema<QuickAddVocabRequest>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<QuickAddVocabResponse>() }
                        HttpStatusCode.BadRequest { description = "STUDENT_NOT_LINKED, VOCAB_LANGUAGE_UNSUPPORTED"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, vocabularyService.getWord(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get student vocabulary word"
                        parameters { path("id") { description = "Student vocab UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<StudentVocabResponse>() }
                            HttpStatusCode.NotFound { description = "VOCABULARY_WORD_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<UpdateStudentVocabRequest> {
                        val result = vocabularyService.updateStatus(call.getPathUUID(), it.status, call.requirePrincipal())
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Set word status"
                        description = "Sets the learning status. Edit the word itself via /vocab-entries/{entryId}."
                        parameters { path("id") { description = "Student vocab UUID" } }
                        requestBody { schema = jsonSchema<UpdateStudentVocabRequest>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<StudentVocabResponse>() } }
                    }

                    delete {
                        vocabularyService.deleteWord(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Remove word from student"
                        description = "Removes the word from the student's vocabulary; the library entry stays."
                        parameters { path("id") { description = "Student vocab UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Removed" } }
                    }

                    post("/review") {
                        call.respond(HttpStatusCode.OK, vocabularyService.reviewWord(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Review word"
                        description = "Marks a word as reviewed (reps+1) and schedules the next review (interval doubles, max 64 days)."
                        parameters { path("id") { description = "Student vocab UUID" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<StudentVocabResponse>() } }
                    }
                }
            }

            route("/api/v1/vocab-entries") {
                get {
                    val params = call.request.queryParameters
                    val result = vocabEntryService.listEntries(
                        principal = call.requirePrincipal(),
                        q = params["q"],
                        topicId = call.getQueryUUID("topicId"),
                        level = params["level"]?.let(LanguageLevel::valueOf),
                        wordType = params["wordType"]?.let(WordType::valueOf),
                        page = PageRequest.from(call),
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "List library entries"
                    description = "The teacher's vocab library (admins: all). Students are forbidden."
                    parameters {
                        query("q") { description = "Lemma substring search"; required = false }
                        query("topicId") { description = "Filter by topic UUID"; required = false }
                        query("level") { description = "Filter by level"; required = false }
                        query("wordType") { description = "Filter by word type"; required = false }
                        query("page") { description = "Page number (1-based, default 1)"; required = false }
                        query("pageSize") { description = "Items per page (default 20, max 100)"; required = false }
                    }
                    responses { HttpStatusCode.OK { schema = jsonSchema<VocabEntryPageResponse>() } }
                }

                get("/lookup") {
                    val params = call.request.queryParameters
                    val lemma = params["lemma"]?.takeIf { it.isNotBlank() }
                        ?: throw BadRequestException("Missing query parameter: lemma")
                    val result = vocabEntryService.lookup(
                        principal = call.requirePrincipal(),
                        lemma = lemma,
                        article = params["article"]?.let(NounArticle::valueOf),
                        wordType = params["wordType"]?.let(WordType::valueOf),
                    )
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Look up library entries by lemma"
                    description = "Exact, case-insensitive lemma match in the teacher's library; use before creating to avoid duplicates."
                    parameters {
                        query("lemma") { description = "Lemma to look up"; required = true }
                        query("article") { description = "DER, DIE or DAS"; required = false }
                        query("wordType") { description = "Word type"; required = false }
                    }
                    responses { HttpStatusCode.OK { schema = jsonSchema<List<VocabEntryResponse>>() } }
                }

                post<VocabEntryInput> {
                    call.respond(HttpStatusCode.Created, vocabEntryService.createEntry(it, call.requirePrincipal()))
                }.describe {
                    summary = "Create library entry"
                    description = "Teacher only. 409 VOCAB_ENTRY_DUPLICATE when lemma+article+wordType already exists in the library."
                    requestBody { schema = jsonSchema<VocabEntryInput>() }
                    responses {
                        HttpStatusCode.Created { schema = jsonSchema<VocabEntryResponse>() }
                        HttpStatusCode.Conflict { description = "VOCAB_ENTRY_DUPLICATE"; schema = jsonSchema<ProblemDetail>() }
                    }
                }

                route("/{id}") {
                    get {
                        call.respond(HttpStatusCode.OK, vocabEntryService.getEntry(call.getPathUUID(), call.requirePrincipal()))
                    }.describe {
                        summary = "Get library entry"
                        parameters { path("id") { description = "Vocab entry UUID" } }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<VocabEntryResponse>() }
                            HttpStatusCode.NotFound { description = "VOCAB_ENTRY_NOT_FOUND"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    put<VocabEntryInput> {
                        call.respond(HttpStatusCode.OK, vocabEntryService.updateEntry(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Replace library entry"
                        description = "Full replace; changes are visible to every student who has the word."
                        parameters { path("id") { description = "Vocab entry UUID" } }
                        requestBody { schema = jsonSchema<VocabEntryInput>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<VocabEntryResponse>() } }
                    }

                    delete {
                        vocabEntryService.deleteEntry(call.getPathUUID(), call.requirePrincipal())
                        call.respond(HttpStatusCode.NoContent)
                    }.describe {
                        summary = "Delete library entry"
                        description = "Also removes the word from every student's vocabulary."
                        parameters { path("id") { description = "Vocab entry UUID" } }
                        responses { HttpStatusCode.NoContent { description = "Deleted" } }
                    }

                    post<AssignVocabRequest>("/assign") {
                        call.respond(HttpStatusCode.OK, vocabEntryService.assign(call.getPathUUID(), it, call.requirePrincipal()))
                    }.describe {
                        summary = "Assign entry to students"
                        description = "Adds the entry to studentIds + group members (or the lesson's confirmed students if neither is given). Students who already have it are skipped."
                        parameters { path("id") { description = "Vocab entry UUID" } }
                        requestBody { schema = jsonSchema<AssignVocabRequest>() }
                        responses { HttpStatusCode.OK { schema = jsonSchema<AssignVocabResponse>() } }
                    }
                }
            }

            route("/api/v1/students/{studentId}") {
                route("/vocab-settings") {
                    get {
                        val result = vocabSettingsService.getSettings(
                            call.getPathUUID("studentId"), call.getQueryUUID("teacherId"), call.requirePrincipal(),
                        )
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Get vocab display setting"
                        description = "Effective setting for the teacher–student pair. Students may read their own (pass teacherId if they have several teachers)."
                        parameters {
                            path("studentId") { description = "Student UUID" }
                            query("teacherId") { description = "Teacher UUID (students/admins only)"; required = false }
                        }
                        responses { HttpStatusCode.OK { schema = jsonSchema<VocabSettingsResponse>() } }
                    }

                    put<VocabDisplaySetting> {
                        val result = vocabSettingsService.putSettings(call.getPathUUID("studentId"), it, call.requirePrincipal())
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Set vocab display setting"
                        description = "Teacher only. fields: any of ru, en, de_explanation."
                        parameters { path("studentId") { description = "Student UUID" } }
                        requestBody { schema = jsonSchema<VocabDisplaySetting>() }
                        responses {
                            HttpStatusCode.OK { schema = jsonSchema<VocabSettingsResponse>() }
                            HttpStatusCode.BadRequest { description = "VOCAB_DISPLAY_FIELD_UNSUPPORTED"; schema = jsonSchema<ProblemDetail>() }
                        }
                    }

                    delete {
                        val result = vocabSettingsService.resetSettings(call.getPathUUID("studentId"), call.requirePrincipal())
                        call.respond(HttpStatusCode.OK, result)
                    }.describe {
                        summary = "Reset vocab display setting"
                        description = "Teacher only. Falls back to the level default (A1–A2: ru; B1+: de_explanation with toggle)."
                        parameters { path("studentId") { description = "Student UUID" } }
                        responses { HttpStatusCode.OK { schema = jsonSchema<VocabSettingsResponse>() } }
                    }
                }

                put<SetStudentLevelRequest>("/level") {
                    val result = vocabSettingsService.setLevel(call.getPathUUID("studentId"), it.level, call.requirePrincipal())
                    call.respond(HttpStatusCode.OK, result)
                }.describe {
                    summary = "Set student level"
                    description = "Teacher only. Sets the student's CEFR level; returns the resulting vocab setting."
                    parameters { path("studentId") { description = "Student UUID" } }
                    requestBody { schema = jsonSchema<SetStudentLevelRequest>() }
                    responses { HttpStatusCode.OK { schema = jsonSchema<VocabSettingsResponse>() } }
                }
            }
        }
    }
}

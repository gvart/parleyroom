package com.gvart.parleyroom.practice.service

import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.service.LearningActivityRecorder
import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.ai.service.AiJobFailure
import com.gvart.parleyroom.ai.service.AiOutputParser
import com.gvart.parleyroom.ai.service.GenerationJobs
import com.gvart.parleyroom.ai.service.Prompts
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.common.transfer.PageRequest
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.common.transfer.exception.ServiceUnavailableException
import com.gvart.parleyroom.common.transfer.exception.TooManyRequestsException
import com.gvart.parleyroom.practice.config.PracticeConfig
import com.gvart.parleyroom.practice.data.StudentVocabSentenceTable
import com.gvart.parleyroom.practice.transfer.ExplanationTranslation
import com.gvart.parleyroom.practice.transfer.SentenceFeedback
import com.gvart.parleyroom.practice.transfer.SentencePageResponse
import com.gvart.parleyroom.practice.transfer.SentenceResponse
import com.gvart.parleyroom.practice.transfer.SentenceWord
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.service.VocabDisplay
import com.gvart.parleyroom.vocabulary.service.VocabularyService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

/** "Write your own sentence" with synchronous AI feedback (API.md "Own sentences"). */
class SentenceService(
    private val config: PracticeConfig,
    private val ai: AiRuntime,
    private val vocabularyService: VocabularyService,
) {

    private data class Request(val prompt: String, val translationLanguage: String?)

    suspend fun create(id: UUID, rawSentence: String, principal: UserPrincipal): SentenceResponse {
        val sentence = rawSentence.trim()
        val request = transaction {
            val row = VocabularyService.findWord(id)
            PracticeAccess.requireOwnWord(row, principal)
            if (sentence.isEmpty()) throw BadRequestException("The sentence is empty", code = "SENTENCE_EMPTY", pointer = "/sentence")
            if (sentence.length > MAX_SENTENCE)
                throw BadRequestException("The sentence is longer than $MAX_SENTENCE characters", code = "SENTENCE_TOO_LONG", pointer = "/sentence")
            if (ai.gateway == null) throw notConfigured()
            val now = OffsetDateTime.now()
            val startOfDay = PracticeTime.startOfDay(principal.id, now)
            val today = StudentVocabSentenceTable.selectAll()
                .where { (StudentVocabSentenceTable.studentId eq principal.id) and (StudentVocabSentenceTable.createdAt greaterEq startOfDay) }
                .count()
            // The daily cap has its own code; AI_RATE_LIMITED stays for provider throttling.
            if (today >= config.sentencesPerDay)
                throw TooManyRequestsException(
                    "At most ${config.sentencesPerDay} sentences per day",
                    code = "PRACTICE_SENTENCE_LIMIT",
                    resetsAt = PracticeTime.endOfDay(principal.id, now),
                )
            buildRequest(row, sentence, principal)
        }
        val gateway = ai.gateway ?: throw notConfigured()

        val completion = try {
            withTimeout(config.sentenceTimeout) {
                GenerationJobs.completeValidated(gateway, Prompts.sentenceFeedbackSystem, request.prompt, MAX_TOKENS) {
                    AiOutputParser.parseSentenceFeedback(it, request.translationLanguage != null)
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw ServiceUnavailableException("The AI did not answer in time", code = "AI_TIMEOUT")
        } catch (e: AiJobFailure) {
            if (e.code == "AI_RATE_LIMITED") throw TooManyRequestsException(e.message ?: "AI rate limited", code = e.code)
            throw ServiceUnavailableException(e.message ?: "The AI request failed", code = e.code)
        }
        val output = completion.value
        val feedback = SentenceFeedback(
            isCorrect = output.isCorrect,
            corrected = output.corrected,
            explanation = output.explanation,
            explanationTranslation = request.translationLanguage?.let { lang -> output.explanationTranslation?.let { ExplanationTranslation(lang, it) } },
            usesWord = output.usesWord,
        )

        return transaction {
            val sentenceId = StudentVocabSentenceTable.insertAndGetId {
                it[studentVocabId] = id
                it[studentId] = principal.id
                it[StudentVocabSentenceTable.sentence] = sentence
                it[StudentVocabSentenceTable.feedback] = feedback
                it[modelId] = gateway.modelId
                it[createdAt] = OffsetDateTime.now()
            }.value
            LearningActivityRecorder.record(principal.id, ActivityKind.VOCAB_SENTENCE, id)
            toResponse(joined().selectAll().where { StudentVocabSentenceTable.id eq sentenceId }.single())
        }
    }

    fun listForWord(id: UUID, principal: UserPrincipal): List<SentenceResponse> = transaction {
        val row = VocabularyService.findWord(id)
        AuthorizationHelper.requireAccessToStudent(row[StudentVocabTable.studentId].value, principal)
        joined().selectAll()
            .where { StudentVocabSentenceTable.studentVocabId eq id }
            .orderBy(StudentVocabSentenceTable.createdAt to SortOrder.DESC)
            .map(::toResponse)
    }

    fun listForStudent(studentId: UUID, principal: UserPrincipal, page: PageRequest): SentencePageResponse = transaction {
        AuthorizationHelper.requireAccessToStudent(studentId, principal)
        val query = joined().selectAll().where { StudentVocabSentenceTable.studentId eq studentId }
        val total = query.count()
        val sentences = query
            .orderBy(StudentVocabSentenceTable.createdAt to SortOrder.DESC)
            .limit(page.pageSize)
            .offset(page.offset)
            .map(::toResponse)
        SentencePageResponse(sentences, total, page.page, page.pageSize)
    }

    /**
     * PRIVACY: the model only gets the sentence, the word's own data, the level and a language code.
     * The explanation is translated for A1–A2 (or no level) when the word's display setting shows a translation.
     */
    private fun buildRequest(row: ResultRow, sentence: String, principal: UserPrincipal): Request {
        val level = UserTable.findByIdOrThrow(principal.id, "User")[UserTable.level]
        val display = vocabularyService.toResponses(listOf(row), principal).single().display
        val translationLanguage = if (level == null || level == LanguageLevel.A1 || level == LanguageLevel.A2)
            display.fields.firstOrNull { it in VocabDisplay.TRANSLATION_LANGUAGES }
        else null
        val targetWord = buildString {
            append("lemma: ").append(row[VocabEntryTable.lemma]).append('\n')
            row[VocabEntryTable.article]?.let { append("article: ").append(it.name.lowercase()).append('\n') }
            append("wordType: ").append(row[VocabEntryTable.wordType].name)
            row[VocabEntryTable.government]?.let { append("\ngovernment: ").append(it) }
        }
        val prompt = Prompts.sentenceFeedback(sentence, targetWord, level?.name ?: "unknown", translationLanguage)
        return Request(prompt, translationLanguage)
    }

    private fun joined() = StudentVocabSentenceTable
        .join(StudentVocabTable, JoinType.INNER, StudentVocabSentenceTable.studentVocabId, StudentVocabTable.id)
        .join(VocabEntryTable, JoinType.INNER, StudentVocabTable.vocabEntryId, VocabEntryTable.id)

    private fun toResponse(row: ResultRow) = SentenceResponse(
        id = row[StudentVocabSentenceTable.id].value.toString(),
        studentVocabId = row[StudentVocabSentenceTable.studentVocabId].value.toString(),
        studentId = row[StudentVocabSentenceTable.studentId].value.toString(),
        entryId = row[VocabEntryTable.id].value.toString(),
        word = SentenceWord(row[VocabEntryTable.lemma], row[VocabEntryTable.article], row[VocabEntryTable.wordType]),
        sentence = row[StudentVocabSentenceTable.sentence],
        feedback = row[StudentVocabSentenceTable.feedback],
        createdAt = row[StudentVocabSentenceTable.createdAt],
    )

    private fun notConfigured() = ServiceUnavailableException("AI is not configured on this server", code = "AI_NOT_CONFIGURED")

    companion object {
        const val MAX_SENTENCE = 300
        const val MAX_TOKENS = 600
    }
}

package com.gvart.parleyroom.practice.service

import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.service.LearningActivityRecorder
import com.gvart.parleyroom.activity.service.StreakService
import com.gvart.parleyroom.ai.config.AiRuntime
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.transfer.exception.BadRequestException
import com.gvart.parleyroom.practice.config.PracticeConfig
import com.gvart.parleyroom.practice.data.PracticeMode
import com.gvart.parleyroom.practice.data.Rating
import com.gvart.parleyroom.practice.data.StudentVocabSentenceTable
import com.gvart.parleyroom.practice.data.VocabReviewTable
import com.gvart.parleyroom.practice.transfer.ArticleCheckResponse
import com.gvart.parleyroom.practice.transfer.IntervalPreview
import com.gvart.parleyroom.practice.transfer.PracticeCard
import com.gvart.parleyroom.practice.transfer.PracticeQueueResponse
import com.gvart.parleyroom.practice.transfer.PracticeStatsResponse
import com.gvart.parleyroom.topic.data.TopicTable
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.security.UserPrincipal
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTable
import com.gvart.parleyroom.vocabulary.data.VocabEntryTopicTable
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.service.VocabularyService
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabResponse
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Flashcards and the article trainer on the FSRS card of each student_vocab row (API.md "Practice"). */
class PracticeService(
    private val config: PracticeConfig,
    private val vocabularyService: VocabularyService,
    private val streakService: StreakService,
    private val ai: AiRuntime,
) {

    data class Filters(val topicId: UUID? = null, val lessonId: UUID? = null, val level: LanguageLevel? = null)

    fun queue(principal: UserPrincipal, mode: PracticeMode, filters: Filters, limit: Int): PracticeQueueResponse = transaction {
        requireStudent(principal)
        val now = OffsetDateTime.now()
        val endOfDay = PracticeTime.endOfDay(principal.id, now)
        val query = VocabularyService.joined().selectAll()
            .where { StudentVocabTable.studentId eq principal.id }
            .andWhere { (StudentVocabTable.state eq FsrsState.NEW.code) or practicableNow(now, endOfDay) }
        filters.lessonId?.let { query.andWhere { StudentVocabTable.lessonId eq it } }
        filters.level?.let { query.andWhere { VocabEntryTable.level eq it } }
        filters.topicId?.let { topicId ->
            val topicIds = topicSubtree(topicId)
            query.andWhere {
                VocabEntryTable.id inSubQuery VocabEntryTopicTable.select(VocabEntryTopicTable.vocabEntryId)
                    .where { VocabEntryTopicTable.topicId inList topicIds }
            }
        }
        if (mode == PracticeMode.ARTICLE)
            query.andWhere { (VocabEntryTable.wordType eq WordType.NOUN) and VocabEntryTable.article.isNotNull() }

        val rows = query.toList()
        var candidates = rows.zip(vocabularyService.toResponses(rows, principal))
        if (mode == PracticeMode.MEANING_TO_DE)
            candidates = candidates.filter { (_, word) -> word.translations.isNotEmpty() || word.explanationDe != null }

        val (fresh, due) = candidates.partition { (row, _) -> row[StudentVocabTable.state] == FsrsState.NEW.code }
        val introduced = newIntroducedToday(principal.id, now)
        val newCards = fresh
            .sortedWith(compareBy({ it.first[StudentVocabTable.addedAt].toInstant() }, { it.first[VocabEntryTable.lemma] }))
            .take((config.newCardsPerDay - introduced).coerceAtLeast(0))
        val cards = (due.sortedBy { it.first[StudentVocabTable.due]!!.toInstant() } + newCards)
            .take(limit)
            .map { (row, word) -> toCard(row, word, mode, now) }

        PracticeQueueResponse(mode, cards, due.size, newCards.size, config.newCardsPerDay, introduced)
    }

    /** Access check before the body is read, so foreign words are 403 / 404 whatever the body. */
    fun requireOwnWord(id: UUID, principal: UserPrincipal) = transaction {
        PracticeAccess.requireOwnWord(VocabularyService.findWord(id), principal)
    }

    fun review(id: UUID, rating: Rating, mode: PracticeMode, responseMs: Int?, principal: UserPrincipal): StudentVocabResponse =
        transaction {
            val row = lockOwnWord(id, principal)
            if (mode == PracticeMode.ARTICLE)
                throw BadRequestException("Use /article for the article trainer", code = "PRACTICE_MODE_INVALID")
            requireResponseMs(responseMs)
            applyReview(row, rating, mode, responseMs)
            vocabularyService.toResponses(listOf(VocabularyService.findWord(id)), principal).single()
        }

    fun checkArticle(id: UUID, article: NounArticle, responseMs: Int?, principal: UserPrincipal): ArticleCheckResponse =
        transaction {
            val row = lockOwnWord(id, principal)
            requireResponseMs(responseMs)
            val correctArticle = row[VocabEntryTable.article]
            if (row[VocabEntryTable.wordType] != WordType.NOUN || correctArticle == null)
                throw BadRequestException("The word is not a noun with an article", code = "NOT_A_NOUN")
            val correct = article == correctArticle
            val rating = if (correct) Rating.GOOD else Rating.AGAIN
            applyReview(row, rating, PracticeMode.ARTICLE, responseMs)
            val word = vocabularyService.toResponses(listOf(VocabularyService.findWord(id)), principal).single()
            ArticleCheckResponse(correct, correctArticle, rating, word)
        }

    fun stats(studentIdParam: UUID?, principal: UserPrincipal): PracticeStatsResponse {
        val studentId = if (principal.role == UserRole.STUDENT) principal.id
        else studentIdParam ?: throw BadRequestException("studentId is required", code = "VALIDATION_FAILED")

        val counts = transaction {
            AuthorizationHelper.requireAccessToStudent(studentId, principal)
            val now = OffsetDateTime.now()
            val startOfDay = PracticeTime.startOfDay(studentId, now)
            val endOfDay = PracticeTime.endOfDay(studentId, now)
            fun words(extra: () -> Op<Boolean>) =
                StudentVocabTable.selectAll().where { (StudentVocabTable.studentId eq studentId) and extra() }.count().toInt()

            val reviewed = { StudentVocabTable.state greater FsrsState.NEW.code }
            val introduced = newIntroducedToday(studentId, now)
            val newTotal = words { StudentVocabTable.state eq FsrsState.NEW.code }
            Counts(
                dueNow = words { practicableNow(now, endOfDay) },
                dueToday = words { reviewed() and (StudentVocabTable.due less endOfDay) },
                newTotal = newTotal,
                newIntroducedToday = introduced,
                newAvailable = minOf(newTotal, (config.newCardsPerDay - introduced).coerceAtLeast(0)),
                reviewedToday = VocabReviewTable.select(VocabReviewTable.studentVocabId)
                    .where { (VocabReviewTable.studentId eq studentId) and (VocabReviewTable.reviewedAt greaterEq startOfDay) }
                    .withDistinct().count().toInt(),
                sentencesToday = StudentVocabSentenceTable.selectAll()
                    .where { (StudentVocabSentenceTable.studentId eq studentId) and (StudentVocabSentenceTable.createdAt greaterEq startOfDay) }
                    .count().toInt(),
            )
        }
        return PracticeStatsResponse(
            dueNow = counts.dueNow,
            dueToday = counts.dueToday,
            newAvailable = counts.newAvailable,
            newTotal = counts.newTotal,
            newLimit = config.newCardsPerDay,
            newIntroducedToday = counts.newIntroducedToday,
            reviewedToday = counts.reviewedToday,
            sentencesToday = counts.sentencesToday,
            sentenceLimit = config.sentencesPerDay,
            aiAvailable = ai.gateway != null,
            streak = streakService.getStreak(studentId, principal),
        )
    }

    private data class Counts(
        val dueNow: Int, val dueToday: Int, val newTotal: Int, val newIntroducedToday: Int,
        val newAvailable: Int, val reviewedToday: Int, val sentencesToday: Int,
    )

    /** Grades the row's FSRS card, logs the review and records streak activity. Inside the caller's transaction. */
    private fun applyReview(row: ResultRow, rating: Rating, mode: PracticeMode, responseMs: Int?) {
        val id = row[StudentVocabTable.id].value
        val studentId = row[StudentVocabTable.studentId].value
        val now = OffsetDateTime.now()
        val before = cardOf(row)
        val next = Fsrs.review(before, rating, now.toInstant())
        val due = OffsetDateTime.ofInstant(next.due, ZoneOffset.UTC)

        StudentVocabTable.update({ StudentVocabTable.id eq id }) {
            it[state] = next.state.code
            it[step] = next.step?.toShort()
            it[stability] = next.stability
            it[difficulty] = next.difficulty
            it[StudentVocabTable.due] = due
            it[lastReview] = now
            it[reps] = row[reps] + 1
            if (before.state == FsrsState.REVIEW && rating == Rating.AGAIN) it[lapses] = row[lapses] + 1
            it[elapsedDays] = before.lastReview?.let { last -> Duration.between(last, now.toInstant()).toDays().toInt() } ?: 0
            it[scheduledDays] = Duration.between(now, due).toDays().toInt()
            it[status] = statusOf(next)
        }
        VocabReviewTable.insert {
            it[studentVocabId] = id
            it[VocabReviewTable.studentId] = studentId
            it[VocabReviewTable.rating] = rating
            it[VocabReviewTable.mode] = mode
            it[stateBefore] = before.state.code
            it[VocabReviewTable.responseMs] = responseMs
            it[reviewedAt] = now
        }
        LearningActivityRecorder.record(studentId, ActivityKind.VOCAB_REVIEW, id)
    }

    fun statusOf(card: FsrsCard): StudentVocabStatus = when (card.state) {
        FsrsState.NEW -> StudentVocabStatus.NEW
        FsrsState.LEARNING, FsrsState.RELEARNING -> StudentVocabStatus.LEARNING
        FsrsState.REVIEW ->
            if ((card.stability ?: 0.0) >= config.learnedStabilityDays) StudentVocabStatus.LEARNED else StudentVocabStatus.REVIEW
    }

    private fun toCard(row: ResultRow, word: StudentVocabResponse, mode: PracticeMode, now: OffsetDateTime): PracticeCard {
        val card = cardOf(row)
        val ratings = if (mode == PracticeMode.ARTICLE) listOf(Rating.AGAIN, Rating.GOOD) else Rating.entries
        val intervals = ratings.associateWith { rating ->
            val due = Fsrs.review(card, rating, now.toInstant()).due!!
            IntervalPreview(OffsetDateTime.ofInstant(due, ZoneOffset.UTC), Duration.between(now.toInstant(), due).seconds)
        }
        // The article trainer must not give the article away (plural, forms and example often do).
        val shown = if (mode == PracticeMode.ARTICLE) word.copy(article = null, plural = null, forms = null, exampleSentence = null) else word
        return PracticeCard(mode, card.state == FsrsState.NEW, shown, intervals)
    }

    private fun cardOf(row: ResultRow) = FsrsCard(
        state = FsrsState.of(row[StudentVocabTable.state]),
        step = row[StudentVocabTable.step]?.toInt(),
        stability = row[StudentVocabTable.stability],
        difficulty = row[StudentVocabTable.difficulty],
        due = row[StudentVocabTable.due]?.toInstant(),
        lastReview = row[StudentVocabTable.lastReview]?.toInstant(),
    )

    /**
     * Reviewed cards that can be practised now: review cards due any time today (the student's day), and
     * learning / relearning cards only once their short intraday step is due. FSRS grades an early
     * review from the actual elapsed time, so serving a review card a few hours early stays correct.
     */
    private fun practicableNow(now: OffsetDateTime, endOfDay: OffsetDateTime): Op<Boolean> =
        ((StudentVocabTable.state eq FsrsState.REVIEW.code) and (StudentVocabTable.due less endOfDay)) or
                ((StudentVocabTable.state inList listOf(FsrsState.LEARNING.code, FsrsState.RELEARNING.code)) and
                        StudentVocabTable.due.lessEq(now))

    /** Cards whose first review happened today (student's timezone): the daily new-card budget. */
    private fun newIntroducedToday(studentId: UUID, now: OffsetDateTime): Int =
        VocabReviewTable.select(VocabReviewTable.studentVocabId)
            .where {
                (VocabReviewTable.studentId eq studentId) and (VocabReviewTable.stateBefore eq FsrsState.NEW.code) and
                        (VocabReviewTable.reviewedAt greaterEq PracticeTime.startOfDay(studentId, now))
            }
            .withDistinct().count().toInt()

    /** The topic and all its descendants. */
    private fun topicSubtree(root: UUID): List<UUID> {
        val all = linkedSetOf(root)
        var frontier = listOf(root)
        while (frontier.isNotEmpty()) {
            frontier = TopicTable.select(TopicTable.id)
                .where { TopicTable.parentId inList frontier }
                .map { it[TopicTable.id].value }
                .filter { all.add(it) }
        }
        return all.toList()
    }

    private fun lockOwnWord(id: UUID, principal: UserPrincipal): ResultRow {
        val row = VocabularyService.joined().selectAll().where { StudentVocabTable.id eq id }.forUpdate().singleOrNull()
            ?: VocabularyService.findWord(id) // throws VOCABULARY_WORD_NOT_FOUND
        PracticeAccess.requireOwnWord(row, principal)
        return row
    }

    private fun requireStudent(principal: UserPrincipal) {
        if (principal.role != UserRole.STUDENT) throw PracticeAccess.studentOnly()
    }

    private fun requireResponseMs(responseMs: Int?) {
        if (responseMs != null && responseMs !in 0..MAX_RESPONSE_MS)
            throw BadRequestException("responseMs must be 0..$MAX_RESPONSE_MS", code = "VALIDATION_FAILED", pointer = "/responseMs")
    }

    companion object {
        const val MAX_RESPONSE_MS = 600_000
        const val MAX_QUEUE_LIMIT = 100
        const val DEFAULT_QUEUE_LIMIT = 20
    }
}

package com.gvart.parleyroom.practice

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.activity.data.ActivityKind
import com.gvart.parleyroom.activity.data.LearningActivityTable
import com.gvart.parleyroom.ai.STUDENT
import com.gvart.parleyroom.ai.STUDENT_2
import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.transfer.ProblemDetail
import com.gvart.parleyroom.library.LibraryFixtures
import com.gvart.parleyroom.practice.data.PracticeMode
import com.gvart.parleyroom.practice.data.Rating
import com.gvart.parleyroom.practice.data.VocabReviewTable
import com.gvart.parleyroom.practice.service.FsrsState
import com.gvart.parleyroom.practice.transfer.ArticleCheckRequest
import com.gvart.parleyroom.practice.transfer.ArticleCheckResponse
import com.gvart.parleyroom.practice.transfer.PracticeQueueResponse
import com.gvart.parleyroom.practice.transfer.PracticeStatsResponse
import com.gvart.parleyroom.practice.transfer.ReviewRequest
import com.gvart.parleyroom.vocabulary.data.NounArticle
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabTable
import com.gvart.parleyroom.vocabulary.data.WordType
import com.gvart.parleyroom.vocabulary.transfer.StudentVocabResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeIntegrationTest : IntegrationTest() {

    private suspend fun HttpClient.review(token: String, id: UUID, rating: Rating, mode: PracticeMode = PracticeMode.DE_TO_MEANING): HttpResponse =
        post("/api/v1/vocabulary/$id/review") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(ReviewRequest(rating, mode, responseMs = 1500))
        }

    private suspend fun HttpClient.rawPost(token: String, path: String, json: String): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(json)
        }

    private suspend fun HttpClient.queue(token: String, query: String = ""): PracticeQueueResponse =
        get("/api/v1/practice/queue$query") { bearerAuth(token) }.body()

    private suspend fun HttpClient.checkArticle(token: String, id: UUID, article: NounArticle): HttpResponse =
        post("/api/v1/vocabulary/$id/article") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(ArticleCheckRequest(article))
        }

    private fun reviewsOf(id: UUID) = transaction {
        VocabReviewTable.selectAll().where { VocabReviewTable.studentVocabId eq id }.toList()
    }

    // -- Review --

    @Test
    fun `review grades the FSRS card, logs it and counts for the streak`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus")

        val first = client.review(token, id, Rating.GOOD).body<StudentVocabResponse>()
        assertEquals(StudentVocabStatus.LEARNING, first.status)
        assertEquals(1, first.reps)
        assertNotNull(first.lastReview)
        val tenMinutes = Duration.between(first.lastReview, first.due).toMinutes()
        assertEquals(10, tenMinutes)

        val row = transaction { StudentVocabTable.selectAll().where { StudentVocabTable.id eq id }.single() }
        assertEquals(FsrsState.LEARNING.code, row[StudentVocabTable.state])
        assertEquals(1.toShort(), row[StudentVocabTable.step])
        assertEquals(2.3065, row[StudentVocabTable.stability]!!, 1e-9)

        val second = client.review(token, id, Rating.GOOD).body<StudentVocabResponse>()
        assertEquals(StudentVocabStatus.REVIEW, second.status)
        assertTrue(Duration.between(second.lastReview, second.due) >= Duration.ofDays(1))

        val log = reviewsOf(id)
        assertEquals(2, log.size)
        assertEquals(listOf(0.toShort(), 1.toShort()), log.sortedBy { it[VocabReviewTable.reviewedAt] }.map { it[VocabReviewTable.stateBefore] })
        assertEquals(1500, log.first()[VocabReviewTable.responseMs])

        val activity = transaction { LearningActivityTable.selectAll().where { LearningActivityTable.userId eq STUDENT }.toList() }
        assertEquals(2, activity.size)
        assertTrue(activity.all { it[LearningActivityTable.kind] == ActivityKind.VOCAB_REVIEW })
    }

    @Test
    fun `again on a review card is a lapse and relearning`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Tisch", state = FsrsState.REVIEW, due = OffsetDateTime.now().minusHours(1))

        val word = client.review(token, id, Rating.AGAIN).body<StudentVocabResponse>()
        assertEquals(StudentVocabStatus.LEARNING, word.status)
        assertEquals(1, word.lapses)
        assertEquals(10, Duration.between(word.lastReview, word.due).toMinutes())
    }

    @Test
    fun `a review card above the stability threshold is LEARNED`() = testApp(mapOf("practice.learned_stability_days" to "8")) {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Buch")
        // EASY on a new card: REVIEW with stability 8.2956
        val word = client.review(token, id, Rating.EASY).body<StudentVocabResponse>()
        assertEquals(StudentVocabStatus.LEARNED, word.status)
    }

    @Test
    fun `only the student reviews their own words`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        val id = PracticeFixtures.word("Haus")

        val teacher = client.review(teacherToken, id, Rating.GOOD)
        assertEquals(HttpStatusCode.Forbidden, teacher.status)
        assertEquals("PRACTICE_STUDENT_ONLY", teacher.body<ProblemDetail>().code)

        val other = client.review(getStudent2Token(client), id, Rating.GOOD)
        assertEquals(HttpStatusCode.Forbidden, other.status)
        assertEquals("PRACTICE_STUDENT_ONLY", other.body<ProblemDetail>().code)

        val missing = client.review(getStudentToken(client), UUID.randomUUID(), Rating.GOOD)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertTrue(reviewsOf(id).isEmpty())
    }

    @Test
    fun `foreign access is rejected before the body is read`() = testApp {
        val client = createJsonClient(this)
        val student2 = getStudent2Token(client)
        val teacher = getTeacherToken(client)
        val id = PracticeFixtures.word("Haus")

        listOf("review", "article", "sentences").forEach { action ->
            listOf(student2, teacher).forEach { token ->
                listOf(null, "", "{}", """{"rating":"NOPE"}""").forEach { body ->
                    val response = client.post("/api/v1/vocabulary/$id/$action") {
                        bearerAuth(token)
                        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                    }
                    assertEquals(HttpStatusCode.Forbidden, response.status, "$action with body $body")
                    assertEquals("PRACTICE_STUDENT_ONLY", response.body<ProblemDetail>().code)
                }
            }
            val missing = client.post("/api/v1/vocabulary/${UUID.randomUUID()}/$action") { bearerAuth(student2) }
            assertEquals(HttpStatusCode.NotFound, missing.status, action)
        }
        assertTrue(reviewsOf(id).isEmpty())
    }

    @Test
    fun `review body is validated`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val id = PracticeFixtures.word("Haus")
        val path = "/api/v1/vocabulary/$id/review"

        val article = client.review(token, id, Rating.GOOD, PracticeMode.ARTICLE)
        assertEquals("PRACTICE_MODE_INVALID", article.body<ProblemDetail>().code)

        assertEquals(HttpStatusCode.BadRequest, client.rawPost(token, path, """{"mode":"DE_TO_MEANING"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.rawPost(token, path, """{"rating":"PERFECT","mode":"DE_TO_MEANING"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.rawPost(token, path, "").status)

        val slow = client.rawPost(token, path, """{"rating":"GOOD","mode":"DE_TO_MEANING","responseMs":600001}""")
        assertEquals("VALIDATION_FAILED", slow.body<ProblemDetail>().code)
        assertTrue(reviewsOf(id).isEmpty())

        val raw = client.rawPost(token, path, """{"rating":"HARD","mode":"MEANING_TO_DE","responseMs":2500}""")
        assertEquals(HttpStatusCode.OK, raw.status)
        assertEquals(PracticeMode.MEANING_TO_DE, reviewsOf(id).single()[VocabReviewTable.mode])
    }

    // -- Queue --

    @Test
    fun `queue puts due cards first, then new cards up to the daily limit`() = testApp(mapOf("practice.new_cards_per_day" to "2")) {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val now = OffsetDateTime.now()
        val dueLater = PracticeFixtures.word("Apfel", state = FsrsState.REVIEW, due = now.minusHours(1))
        val dueEarlier = PracticeFixtures.word("Birne", state = FsrsState.REVIEW, due = now.minusDays(2))
        PracticeFixtures.word("Zukunft", state = FsrsState.REVIEW, due = now.plusDays(3)) // not due
        val newOld = PracticeFixtures.word("Kirsche", addedAt = now.minusDays(10))
        val newMid = PracticeFixtures.word("Dattel", addedAt = now.minusDays(5))
        PracticeFixtures.word("Feige", addedAt = now.minusDays(1))

        val queue = client.queue(token)
        assertEquals(listOf(dueEarlier, dueLater, newOld, newMid).map(UUID::toString), queue.cards.map { it.word.id })
        assertEquals(listOf(false, false, true, true), queue.cards.map { it.isNew })
        assertEquals(2, queue.dueCount)
        assertEquals(2, queue.newCount)
        assertEquals(2, queue.newLimit)
        assertEquals(0, queue.newIntroducedToday)

        assertEquals(1, client.queue(token, "?limit=1").cards.size)

        // Introducing a new card uses up the daily budget, in any mode.
        client.checkArticle(token, newOld, NounArticle.DAS)
        val after = client.queue(token)
        assertEquals(1, after.newIntroducedToday)
        assertEquals(1, after.newCount)
        assertEquals(newMid.toString(), after.cards.last().word.id)
        assertFalse(after.cards.any { it.word.id == newOld.toString() }) // learning step: due in 10 minutes
    }

    @Test
    fun `queue previews the next interval per rating`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        PracticeFixtures.word("Haus")
        val card = client.queue(token).cards.single()
        assertEquals(Rating.entries.toSet(), card.intervals.keys)
        assertEquals(60, card.intervals[Rating.AGAIN]!!.seconds)
        assertEquals(330, card.intervals[Rating.HARD]!!.seconds)
        assertEquals(600, card.intervals[Rating.GOOD]!!.seconds)
        assertEquals(8 * 86_400L, card.intervals[Rating.EASY]!!.seconds)
    }

    @Test
    fun `queue filters by topic subtree, lesson and level and is student only`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val alltag = LibraryFixtures.topic("Alltag")
        val haushalt = LibraryFixtures.topic("Haushalt", parentId = alltag)
        val reisen = LibraryFixtures.topic("Reisen")
        val kanne = PracticeFixtures.word("Kanne", topicIds = listOf(haushalt))
        val tag = PracticeFixtures.word("Tag", topicIds = listOf(alltag))
        PracticeFixtures.word("Koffer", topicIds = listOf(reisen))

        val folder = client.queue(token, "?topicId=$alltag").cards.map { it.word.id }.toSet()
        assertEquals(setOf(kanne, tag).map(UUID::toString).toSet(), folder)
        assertEquals(listOf(kanne.toString()), client.queue(token, "?topicId=$haushalt").cards.map { it.word.id })
        assertTrue(client.queue(token, "?level=C2").cards.isEmpty())

        val bad = client.get("/api/v1/practice/queue?mode=SPEAKING") { bearerAuth(token) }
        assertEquals("PRACTICE_MODE_INVALID", bad.body<ProblemDetail>().code)

        val teacher = client.get("/api/v1/practice/queue") { bearerAuth(getTeacherToken(client)) }
        assertEquals(HttpStatusCode.Forbidden, teacher.status)
        assertEquals("PRACTICE_STUDENT_ONLY", teacher.body<ProblemDetail>().code)
    }

    @Test
    fun `practice cards follow the display setting`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        PracticeFixtures.setLevel(LanguageLevel.B1)
        PracticeFixtures.word("Haus")

        val word = client.queue(token, "?mode=DE_TO_MEANING").cards.single().word
        assertEquals(listOf("de_explanation"), word.display.fields)
        assertEquals("Erklärung: Haus", word.explanationDe)
        assertEquals(emptyMap(), word.translations)
        assertEquals(mapOf("ru" to "Haus-ru", "en" to "Haus-en"), word.revealTranslations)
    }

    @Test
    fun `meaning to German skips words without a visible meaning`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        // No level: the student sees Russian only, toggle off.
        val visible = PracticeFixtures.word("Haus")
        PracticeFixtures.word("Baum", translations = mapOf("en" to "tree"), explanationDe = null)

        val cards = client.queue(token, "?mode=MEANING_TO_DE").cards
        assertEquals(listOf(visible.toString()), cards.map { it.word.id })
        assertEquals(mapOf("ru" to "Haus-ru"), cards.single().word.translations)
        assertEquals(2, client.queue(token, "?mode=DE_TO_MEANING").cards.size)
    }

    // -- Article trainer --

    @Test
    fun `article mode only has nouns with an article and hides the article`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val noun = PracticeFixtures.word("Haus", article = NounArticle.DAS)
        PracticeFixtures.word("gehen", wordType = WordType.VERB)
        PracticeFixtures.word("Leute", article = null)

        val card = client.queue(token, "?mode=ARTICLE").cards.single()
        assertEquals(noun.toString(), card.word.id)
        assertEquals("Haus", card.word.lemma)
        assertNull(card.word.article)
        assertNull(card.word.plural)
        assertNull(card.word.forms)
        assertNull(card.word.exampleSentence)
        assertEquals(setOf(Rating.AGAIN, Rating.GOOD), card.intervals.keys)
    }

    @Test
    fun `article check grades GOOD or AGAIN on the same card`() = testApp {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val haus = PracticeFixtures.word("Haus", article = NounArticle.DAS)
        val lampe = PracticeFixtures.word("Lampe", article = NounArticle.DIE)
        val verb = PracticeFixtures.word("gehen", wordType = WordType.VERB)

        val right = client.checkArticle(token, haus, NounArticle.DAS).body<ArticleCheckResponse>()
        assertTrue(right.correct)
        assertEquals(Rating.GOOD, right.rating)
        assertEquals(NounArticle.DAS, right.correctArticle)
        assertEquals(1, right.word.reps)

        val wrong = client.rawPost(token, "/api/v1/vocabulary/$lampe/article", """{"article":"DER","responseMs":900}""")
            .body<ArticleCheckResponse>()
        assertFalse(wrong.correct)
        assertEquals(Rating.AGAIN, wrong.rating)
        assertEquals(NounArticle.DIE, wrong.correctArticle)
        assertEquals(PracticeMode.ARTICLE, reviewsOf(lampe).single()[VocabReviewTable.mode])
        assertEquals(Rating.AGAIN, reviewsOf(lampe).single()[VocabReviewTable.rating])

        val notNoun = client.checkArticle(token, verb, NounArticle.DER)
        assertEquals(HttpStatusCode.BadRequest, notNoun.status)
        assertEquals("NOT_A_NOUN", notNoun.body<ProblemDetail>().code)

        val teacher = client.checkArticle(getTeacherToken(client), haus, NounArticle.DAS)
        assertEquals("PRACTICE_STUDENT_ONLY", teacher.body<ProblemDetail>().code)
    }

    // -- Stats --

    @Test
    fun `stats count due, new, reviewed and the streak`() = testApp(mapOf("practice.new_cards_per_day" to "3")) {
        val client = createJsonClient(this)
        val token = getStudentToken(client)
        val now = OffsetDateTime.now()
        val due = PracticeFixtures.word("Apfel", state = FsrsState.REVIEW, due = now.minusHours(1))
        PracticeFixtures.word("Birne", state = FsrsState.REVIEW, due = now.plusDays(5))
        val fresh = PracticeFixtures.word("Kirsche")
        PracticeFixtures.word("Dattel")
        PracticeFixtures.word("Feige")
        PracticeFixtures.word("Mango")

        val before = client.get("/api/v1/practice/stats") { bearerAuth(token) }.body<PracticeStatsResponse>()
        assertEquals(1, before.dueNow)
        assertEquals(4, before.newTotal)
        assertEquals(3, before.newAvailable)
        assertEquals(0, before.reviewedToday)
        assertTrue(before.aiAvailable)
        assertFalse(before.streak.todayDone)

        client.review(token, due, Rating.GOOD)
        client.review(token, fresh, Rating.AGAIN)
        client.review(token, fresh, Rating.GOOD)

        val after = client.get("/api/v1/practice/stats") { bearerAuth(token) }.body<PracticeStatsResponse>()
        assertEquals(0, after.dueNow)
        assertEquals(1, after.dueToday) // the learning card, due in 10 minutes
        assertEquals(3, after.newTotal)
        assertEquals(1, after.newIntroducedToday)
        assertEquals(2, after.newAvailable)
        assertEquals(2, after.reviewedToday)
        assertTrue(after.streak.todayDone)
        assertEquals(1, after.streak.current)
    }

    @Test
    fun `teachers read stats of their students only`() = testApp {
        val client = createJsonClient(this)
        val teacherToken = getTeacherToken(client)
        PracticeFixtures.word("Haus")

        val own = client.get("/api/v1/practice/stats?studentId=$STUDENT_ID") { bearerAuth(teacherToken) }
        assertEquals(HttpStatusCode.OK, own.status)
        assertEquals(1, own.body<PracticeStatsResponse>().newTotal)

        val missing = client.get("/api/v1/practice/stats") { bearerAuth(teacherToken) }
        assertEquals("VALIDATION_FAILED", missing.body<ProblemDetail>().code)

        val unrelated = client.get("/api/v1/practice/stats?studentId=$STUDENT_2") { bearerAuth(teacherToken) }
        assertEquals(HttpStatusCode.Forbidden, unrelated.status)

        // A student always gets their own numbers.
        val student2 = client.get("/api/v1/practice/stats?studentId=$STUDENT_ID") { bearerAuth(getStudent2Token(client)) }
            .body<PracticeStatsResponse>()
        assertEquals(0, student2.newTotal)
    }

    @Test
    fun `stats report AI as unavailable when it is off`() = testApp(mapOf("ai.provider" to "anthropic", "ai.anthropic_api_key" to "")) {
        val client = createJsonClient(this)
        val stats = client.get("/api/v1/practice/stats") { bearerAuth(getStudentToken(client)) }.body<PracticeStatsResponse>()
        assertFalse(stats.aiAvailable)
        assertEquals(30, stats.sentenceLimit)
    }
}

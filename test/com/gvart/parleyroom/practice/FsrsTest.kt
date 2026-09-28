package com.gvart.parleyroom.practice

import com.gvart.parleyroom.practice.data.Rating
import com.gvart.parleyroom.practice.service.Fsrs
import com.gvart.parleyroom.practice.service.FsrsCard
import com.gvart.parleyroom.practice.service.FsrsState
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reference values from py-fsrs v6.3.2 (tests/test_basic.py and the same scheduler run with enable_fuzzing=False). */
class FsrsTest {

    private val start: Instant = Instant.parse("2022-11-29T12:30:00Z")

    private data class Expected(val state: FsrsState, val step: Int?, val stability: Double, val difficulty: Double, val intervalSeconds: Long)

    @Test
    fun `review sequence matches py-fsrs`() {
        val ratings = List(6) { Rating.GOOD } + List(2) { Rating.AGAIN } + List(5) { Rating.GOOD }
        val expected = listOf(
            Expected(FsrsState.LEARNING, 1, 2.3065, 2.118103970459016, 600),
            Expected(FsrsState.REVIEW, null, 2.3065, 2.111214235785395, 172_800),
            Expected(FsrsState.REVIEW, null, 10.971048263078135, 2.1043313908464483, 950_400),
            Expected(FsrsState.REVIEW, null, 46.316858440073425, 2.0974554287524403, 3_974_400),
            Expected(FsrsState.REVIEW, null, 162.99981577472244, 2.0905863426205262, 14_083_200),
            Expected(FsrsState.REVIEW, null, 497.8765551245907, 2.083724125574744, 43_027_200),
            Expected(FsrsState.RELEARNING, 0, 6.890412507565338, 7.383202320049203, 600),
            Expected(FsrsState.RELEARNING, 0, 2.154598374301973, 9.125104766121234, 600),
            Expected(FsrsState.REVIEW, null, 2.154598374301973, 9.11120803065195, 172_800),
            Expected(FsrsState.REVIEW, null, 3.9831233795187773, 9.097325191918136, 345_600),
            Expected(FsrsState.REVIEW, null, 7.236254476883319, 9.083456236023055, 604_800),
            Expected(FsrsState.REVIEW, null, 12.483043578674472, 9.06960114908387, 1_036_800),
            Expected(FsrsState.REVIEW, null, 20.77035728499242, 9.055759917231622, 1_814_400),
        )

        var card = FsrsCard()
        var now = start
        ratings.zip(expected).forEachIndexed { i, (rating, want) ->
            card = Fsrs.review(card, rating, now)
            assertEquals(want.state, card.state, "state after review $i")
            assertEquals(want.step, card.step, "step after review $i")
            assertEquals(want.stability, card.stability!!, 1e-9, "stability after review $i")
            assertEquals(want.difficulty, card.difficulty!!, 1e-9, "difficulty after review $i")
            assertEquals(want.intervalSeconds, Duration.between(now, card.due).seconds, "interval after review $i")
            now = card.due!!
        }
    }

    @Test
    fun `first review per rating matches py-fsrs`() {
        val expected = mapOf(
            Rating.AGAIN to Expected(FsrsState.LEARNING, 0, 0.212, 6.4133, 60),
            Rating.HARD to Expected(FsrsState.LEARNING, 0, 1.2931, 5.112170705601056, 330),
            Rating.GOOD to Expected(FsrsState.LEARNING, 1, 2.3065, 2.118103970459016, 600),
            Rating.EASY to Expected(FsrsState.REVIEW, null, 8.2956, 1.0, 691_200),
        )
        expected.forEach { (rating, want) ->
            val card = Fsrs.review(FsrsCard(), rating, start)
            assertEquals(want.state, card.state, "$rating")
            assertEquals(want.step, card.step, "$rating")
            assertEquals(want.stability, card.stability!!, 1e-9, "$rating")
            assertEquals(want.difficulty, card.difficulty!!, 1e-9, "$rating")
            assertEquals(want.intervalSeconds, Duration.between(start, card.due).seconds, "$rating")
            assertEquals(start, card.lastReview)
        }
    }

    @Test
    fun `memo state matches py-fsrs`() {
        // py-fsrs test_memo_state: reviews at fixed day offsets.
        val ratings = listOf(Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD)
        val offsets = listOf(0L, 0, 1, 3, 8, 21)
        var card = FsrsCard()
        var now = start
        ratings.zip(offsets).forEach { (rating, days) ->
            now = now.plus(Duration.ofDays(days))
            card = Fsrs.review(card, rating, now)
        }
        assertEquals(53.62691, card.stability!!, 1e-4)
        assertEquals(6.3574867, card.difficulty!!, 1e-4)
    }

    @Test
    fun `repeated easy reviews bottom out at difficulty 1`() {
        var card = FsrsCard()
        repeat(10) { i -> card = Fsrs.review(card, Rating.EASY, start.plusNanos(i * 1000L)) }
        assertEquals(1.0, card.difficulty)
    }

    @Test
    fun `stability never drops below the minimum`() {
        var card = FsrsCard()
        repeat(1000) { card = Fsrs.review(card, Rating.AGAIN, card.due?.plus(Duration.ofDays(1)) ?: start) }
        assertTrue(card.stability!! >= 0.001)
    }

    @Test
    fun `again in review goes to relearning for 10 minutes`() {
        var card = Fsrs.review(FsrsCard(), Rating.EASY, start)
        card = Fsrs.review(card, Rating.AGAIN, card.due!!)
        assertEquals(FsrsState.RELEARNING, card.state)
        assertEquals(0, card.step)
        assertEquals(Duration.ofMinutes(10), Duration.between(card.lastReview, card.due))

        card = Fsrs.review(card, Rating.GOOD, card.due!!)
        assertEquals(FsrsState.REVIEW, card.state)
        assertNull(card.step)
        assertTrue(Duration.between(card.lastReview, card.due) >= Duration.ofDays(1))
    }

    @Test
    fun `retrievability is 0 for new cards and within 0 to 1 otherwise`() {
        assertEquals(0.0, Fsrs.retrievability(FsrsCard(), start))
        val card = Fsrs.review(FsrsCard(), Rating.GOOD, start)
        val r = Fsrs.retrievability(card, start.plus(Duration.ofDays(3)))
        assertTrue(r > 0 && r < 1)
    }
}

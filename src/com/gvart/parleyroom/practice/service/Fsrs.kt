package com.gvart.parleyroom.practice.service

import com.gvart.parleyroom.practice.data.Rating
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.pow

/** FSRS card state; [code] is `student_vocab.state`. NEW = never reviewed (py-fsrs: Learning, step 0). */
enum class FsrsState(val code: Short) {
    NEW(0), LEARNING(1), REVIEW(2), RELEARNING(3);

    companion object {
        fun of(code: Short): FsrsState = entries.first { it.code == code }
    }
}

data class FsrsCard(
    val state: FsrsState = FsrsState.NEW,
    val step: Int? = null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Instant? = null,
    val lastReview: Instant? = null,
)

/**
 * FSRS-6 scheduler: a port of py-fsrs v6.3.2 `Scheduler.review_card` with the default parameters,
 * desired retention 0.9, learning steps 1 min / 10 min, relearning step 10 min and no fuzz.
 */
object Fsrs {

    val PARAMETERS = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
        1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )
    private const val DESIRED_RETENTION = 0.9
    private val LEARNING_STEPS = listOf(Duration.ofMinutes(1), Duration.ofMinutes(10))
    private val RELEARNING_STEPS = listOf(Duration.ofMinutes(10))
    private const val MAXIMUM_INTERVAL = 36500
    private const val STABILITY_MIN = 0.001
    private const val MIN_DIFFICULTY = 1.0
    private const val MAX_DIFFICULTY = 10.0

    private val w = PARAMETERS
    private val DECAY = -w[20]
    private val FACTOR = 0.9.pow(1 / DECAY) - 1

    fun review(card: FsrsCard, rating: Rating, now: Instant): FsrsCard {
        val daysSinceLastReview = card.lastReview?.let { Duration.between(it, now).toDays() }
        val sameDay = daysSinceLastReview != null && daysSinceLastReview < 1
        var state = card.state
        var step = card.step
        val stability: Double
        val difficulty: Double
        val interval: Duration

        when (card.state) {
            FsrsState.NEW, FsrsState.LEARNING, FsrsState.RELEARNING -> {
                val relearning = card.state == FsrsState.RELEARNING
                val steps = if (relearning) RELEARNING_STEPS else LEARNING_STEPS
                val currentStep = step ?: 0
                if (card.stability == null || card.difficulty == null) {
                    stability = initialStability(rating)
                    difficulty = clampDifficulty(initialDifficulty(rating))
                } else if (sameDay) {
                    stability = shortTermStability(card.stability, rating)
                    difficulty = nextDifficulty(card.difficulty, rating)
                } else {
                    stability = nextStability(card.difficulty, card.stability, retrievability(card, now), rating)
                    difficulty = nextDifficulty(card.difficulty, rating)
                }

                if (steps.isEmpty() || (currentStep >= steps.size && rating != Rating.AGAIN)) {
                    state = FsrsState.REVIEW; step = null
                    interval = days(nextInterval(stability))
                } else {
                    state = if (relearning) FsrsState.RELEARNING else FsrsState.LEARNING
                    when (rating) {
                        Rating.AGAIN -> { step = 0; interval = steps[0] }
                        Rating.HARD -> {
                            step = currentStep
                            interval = when {
                                currentStep == 0 && steps.size == 1 -> steps[0].multipliedBy(3).dividedBy(2)
                                currentStep == 0 -> steps[0].plus(steps[1]).dividedBy(2)
                                else -> steps[currentStep]
                            }
                        }
                        Rating.GOOD -> if (currentStep + 1 == steps.size) {
                            state = FsrsState.REVIEW; step = null
                            interval = days(nextInterval(stability))
                        } else {
                            step = currentStep + 1
                            interval = steps[currentStep + 1]
                        }
                        Rating.EASY -> {
                            state = FsrsState.REVIEW; step = null
                            interval = days(nextInterval(stability))
                        }
                    }
                }
            }

            FsrsState.REVIEW -> {
                val s = requireNotNull(card.stability)
                val d = requireNotNull(card.difficulty)
                stability = if (sameDay) shortTermStability(s, rating)
                else nextStability(d, s, retrievability(card, now), rating)
                difficulty = nextDifficulty(d, rating)
                if (rating == Rating.AGAIN && RELEARNING_STEPS.isNotEmpty()) {
                    state = FsrsState.RELEARNING; step = 0
                    interval = RELEARNING_STEPS[0]
                } else {
                    interval = days(nextInterval(stability))
                }
            }
        }

        return FsrsCard(state, step, stability, difficulty, due = now.plus(interval), lastReview = now)
    }

    /** Predicted probability of recall at [now]; 0 for a card that was never reviewed. */
    fun retrievability(card: FsrsCard, now: Instant): Double {
        val last = card.lastReview ?: return 0.0
        val s = card.stability ?: return 0.0
        val elapsed = maxOf(0L, Duration.between(last, now).toDays())
        return (1 + FACTOR * elapsed / s).pow(DECAY)
    }

    private fun days(n: Int): Duration = Duration.ofDays(n.toLong())

    private fun initialStability(rating: Rating): Double = maxOf(w[rating.value - 1], STABILITY_MIN)

    private fun initialDifficulty(rating: Rating): Double = w[4] - exp(w[5] * (rating.value - 1)) + 1

    /** Python's round() rounds half to even, like [Math.rint]. */
    private fun nextInterval(stability: Double): Int {
        val interval = Math.rint((stability / FACTOR) * (DESIRED_RETENTION.pow(1 / DECAY) - 1)).toInt()
        return interval.coerceIn(1, MAXIMUM_INTERVAL)
    }

    private fun shortTermStability(stability: Double, rating: Rating): Double {
        var increase = exp(w[17] * (rating.value - 3 + w[18])) * stability.pow(-w[19])
        if (rating != Rating.AGAIN) increase = maxOf(increase, 1.0)
        return maxOf(stability * increase, STABILITY_MIN)
    }

    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val delta = -(w[6] * (rating.value - 3))
        val damped = difficulty + (10.0 - difficulty) * delta / 9.0
        return clampDifficulty(w[7] * initialDifficulty(Rating.EASY) + (1 - w[7]) * damped)
    }

    private fun nextStability(difficulty: Double, stability: Double, retrievability: Double, rating: Rating): Double {
        val next = if (rating == Rating.AGAIN) {
            val longTerm = w[11] * difficulty.pow(-w[12]) * ((stability + 1).pow(w[13]) - 1) * exp((1 - retrievability) * w[14])
            val shortTerm = stability / exp(w[17] * w[18])
            minOf(longTerm, shortTerm)
        } else {
            val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
            val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
            stability * (1 + exp(w[8]) * (11 - difficulty) * stability.pow(-w[9]) *
                    (exp((1 - retrievability) * w[10]) - 1) * hardPenalty * easyBonus)
        }
        return maxOf(next, STABILITY_MIN)
    }

    private fun clampDifficulty(difficulty: Double): Double = difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
}

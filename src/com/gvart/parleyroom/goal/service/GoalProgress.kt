package com.gvart.parleyroom.goal.service

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class GoalTrackStatus { AHEAD, ON_TRACK, BEHIND, NO_ROADMAP }

/** A milestone as progress sees it: its weight and how many of its items are DONE. */
data class MilestoneCounts(val weight: Double, val items: Int, val done: Int)

/** Decision D-002: weighted item progress against elapsed time, all in percent (0..100). */
object GoalProgress {

    const val TOLERANCE = 5.0

    /** Σ weight × done/items over milestones with items, / Σ their weight; null without any item. */
    fun progress(milestones: List<MilestoneCounts>): Double? {
        val counted = milestones.filter { it.items > 0 }
        val weights = counted.sumOf { it.weight }
        if (counted.isEmpty() || weights <= 0.0) return null
        return 100 * counted.sumOf { it.weight * it.done / it.items } / weights
    }

    fun milestone(counts: MilestoneCounts): Double? = if (counts.items == 0) null else 100.0 * counts.done / counts.items

    fun elapsed(start: LocalDate, deadline: LocalDate, today: LocalDate): Double {
        val span = ChronoUnit.DAYS.between(start, deadline)
        if (span <= 0) return if (today < deadline) 0.0 else 100.0
        return (100.0 * ChronoUnit.DAYS.between(start, today) / span).coerceIn(0.0, 100.0)
    }

    fun status(progress: Double?, elapsed: Double): GoalTrackStatus = when {
        progress == null -> GoalTrackStatus.NO_ROADMAP
        progress > elapsed + TOLERANCE -> GoalTrackStatus.AHEAD
        progress < elapsed - TOLERANCE -> GoalTrackStatus.BEHIND
        else -> GoalTrackStatus.ON_TRACK
    }
}

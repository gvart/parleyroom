package com.gvart.parleyroom.goal

import com.gvart.parleyroom.goal.service.GoalProgress
import com.gvart.parleyroom.goal.service.GoalTrackStatus
import com.gvart.parleyroom.goal.service.MilestoneCounts
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalProgressTest {

    private val start = LocalDate.of(2026, 1, 1)
    private val deadline = LocalDate.of(2026, 1, 11)

    @Test
    fun `progress weighs milestones and skips those without items`() {
        // (1 × 1/2 + 3 × 1/4) / (1 + 3) = 1.25 / 4 = 31.25 %; the empty milestone does not count.
        val progress = GoalProgress.progress(
            listOf(MilestoneCounts(1.0, 2, 1), MilestoneCounts(3.0, 4, 1), MilestoneCounts(5.0, 0, 0)),
        )
        assertEquals(31.25, progress)
    }

    @Test
    fun `equal weights average the milestone shares`() {
        assertEquals(50.0, GoalProgress.progress(listOf(MilestoneCounts(1.0, 4, 4), MilestoneCounts(1.0, 3, 0))))
    }

    @Test
    fun `no roadmap or only empty milestones has no progress`() {
        assertNull(GoalProgress.progress(emptyList()))
        assertNull(GoalProgress.progress(listOf(MilestoneCounts(1.0, 0, 0))))
        assertEquals(GoalTrackStatus.NO_ROADMAP, GoalProgress.status(null, 40.0))
    }

    @Test
    fun `elapsed is clamped to 0 to 100`() {
        assertEquals(0.0, GoalProgress.elapsed(start, deadline, start.minusDays(3)))
        assertEquals(0.0, GoalProgress.elapsed(start, deadline, start))
        assertEquals(40.0, GoalProgress.elapsed(start, deadline, LocalDate.of(2026, 1, 5)))
        assertEquals(100.0, GoalProgress.elapsed(start, deadline, deadline))
        assertEquals(100.0, GoalProgress.elapsed(start, deadline, deadline.plusDays(30)))
    }

    @Test
    fun `status has a 5 point tolerance either way`() {
        assertEquals(GoalTrackStatus.ON_TRACK, GoalProgress.status(45.0, 40.0))
        assertEquals(GoalTrackStatus.ON_TRACK, GoalProgress.status(35.0, 40.0))
        assertEquals(GoalTrackStatus.AHEAD, GoalProgress.status(45.1, 40.0))
        assertEquals(GoalTrackStatus.BEHIND, GoalProgress.status(34.9, 40.0))
    }

    @Test
    fun `milestone progress is done over items`() {
        assertEquals(25.0, GoalProgress.milestone(MilestoneCounts(1.0, 4, 1)))
        assertNull(GoalProgress.milestone(MilestoneCounts(1.0, 0, 0)))
    }
}

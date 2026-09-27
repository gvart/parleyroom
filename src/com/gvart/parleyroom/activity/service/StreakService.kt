package com.gvart.parleyroom.activity.service

import com.gvart.parleyroom.activity.data.LearningActivityTable
import com.gvart.parleyroom.activity.transfer.StreakDay
import com.gvart.parleyroom.activity.transfer.StreakResponse
import com.gvart.parleyroom.common.service.AuthorizationHelper
import com.gvart.parleyroom.common.service.findByIdOrThrow
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class StreakService {

    fun getStreak(userId: UUID, principal: UserPrincipal): StreakResponse = transaction {
        if (principal.id != userId) AuthorizationHelper.requireAccessToStudent(userId, principal)

        val user = UserTable.findByIdOrThrow(userId, "User")
        val zone = runCatching { ZoneId.of(user[UserTable.timezone]) }.getOrDefault(ZoneOffset.UTC)

        val activeDays = LearningActivityTable.select(LearningActivityTable.occurredAt)
            .where { LearningActivityTable.userId eq userId }
            .mapTo(HashSet()) { it[LearningActivityTable.occurredAt].atZoneSameInstant(zone).toLocalDate() }

        computeStreak(activeDays, LocalDate.now(zone))
    }

    private fun computeStreak(activeDays: Set<LocalDate>, today: LocalDate): StreakResponse {
        val todayDone = today in activeDays

        // The streak isn't broken until the day is over, so count back from
        // yesterday when today has no activity yet.
        var current = 0
        var day = if (todayDone) today else today.minusDays(1)
        while (day in activeDays) {
            current++
            day = day.minusDays(1)
        }

        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (d in activeDays.sorted()) {
            run = if (previous != null && previous.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            previous = d
        }

        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week = (0L until 7L).map { offset ->
            val date = monday.plusDays(offset)
            StreakDay(date = date.toString(), active = date in activeDays)
        }

        return StreakResponse(current = current, longest = longest, todayDone = todayDone, week = week)
    }
}

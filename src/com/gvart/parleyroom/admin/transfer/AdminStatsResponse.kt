package com.gvart.parleyroom.admin.transfer

import kotlinx.serialization.Serializable

@Serializable
data class AdminStatsResponse(
    val users: UserStats,
    val security: SecurityStats,
    val activity: ActivityStats,
    val domain: DomainStats,
)

@Serializable
data class UserStats(
    val total: Long,
    val byRole: Map<String, Long>,
    val byStatus: Map<String, Long>,
)

@Serializable
data class SecurityStats(
    val currentlyLocked: Long,
    val withFailedAttempts: Long,
    val pendingInvitations: Long,
)

@Serializable
data class ActivityStats(
    val registeredLast7Days: Long,
    val registeredLast30Days: Long,
    val activeRefreshTokens: Long,
)

@Serializable
data class DomainStats(
    val lessons: Long,
    val homework: Long,
    val materials: Long,
    val vocabularyWords: Long,
    val learningGoals: Long,
)

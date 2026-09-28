package com.gvart.parleyroom.goal.transfer

import com.gvart.parleyroom.common.data.LanguageLevel
import com.gvart.parleyroom.common.serialization.OffsetDateTimeSerializer
import com.gvart.parleyroom.goal.data.GoalStatus
import com.gvart.parleyroom.goal.data.GoalType
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/** Enums and dates as raw strings so bad values are 400 GOAL_INVALID with a pointer. */
@Serializable
data class GoalInput(
    val studentId: String? = null,
    val type: String? = null,
    val examName: String? = null,
    val targetLevel: String? = null,
    val targetDate: String? = null,
    val note: String? = null,
)

@Serializable
data class GoalPatch(
    val examName: String? = null,
    val targetLevel: String? = null,
    val targetDate: String? = null,
    val clearTargetDate: Boolean = false,
    val note: String? = null,
    val clearNote: Boolean = false,
    val status: String? = null,
)

@Serializable
data class GoalGrammarCounts(val total: Int, val practiced: Int, val covered: Int, val needsWork: Int, val notCovered: Int)

@Serializable
data class GoalTopicCounts(val total: Int, val covered: Int)

@Serializable
data class GoalProgress(
    val percent: Int? = null,
    val checklistEmpty: Boolean,
    val grammar: GoalGrammarCounts,
    val topics: GoalTopicCounts,
    /** Breakdown under the goal bar: grammar topics practised in homework (PRACTICED) of all at the target level. */
    val grammarDone: Int,
    val grammarTotal: Int,
    /** Topics covered (lesson or words) of all relevant to the target level. */
    val topicsDone: Int,
    val topicsTotal: Int,
    val daysLeft: Int? = null,
    val expectedPercent: Int? = null,
    val onTrack: Boolean? = null,
)

@Serializable
data class GoalResponse(
    val id: String,
    val studentId: String,
    val teacherId: String,
    val type: GoalType,
    val examName: String? = null,
    val targetLevel: LanguageLevel,
    val targetDate: String? = null,
    val note: String? = null,
    val status: GoalStatus,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val statusChangedAt: OffsetDateTime? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val updatedAt: OffsetDateTime,
    val baselinePercent: Int? = null,
    val progress: GoalProgress,
)

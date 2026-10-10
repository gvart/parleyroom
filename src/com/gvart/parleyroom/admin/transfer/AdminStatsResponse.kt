package com.gvart.parleyroom.admin.transfer

import com.gvart.parleyroom.homework.data.HomeworkStatus
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.user.data.UserRole
import com.gvart.parleyroom.user.data.UserStatus
import com.gvart.parleyroom.vocabulary.data.StudentVocabStatus
import kotlinx.serialization.Serializable

/** Platform metrics for the admin dashboard. Dates are ISO `YYYY-MM-DD`, inclusive UTC days. */
@Serializable
data class AdminStatsResponse(
    val from: String,
    val to: String,
    val users: UserStats,
    val lessons: LessonStats,
    val homework: HomeworkStats,
    val practice: PracticeStats,
    val content: ContentStats,
)

@Serializable
data class UserStats(
    val total: Long,
    val byRole: Map<UserRole, Long>,
    val byStatus: Map<UserStatus, Long>,
    val newInRange: Long,
    val active7d: Long,
    val active30d: Long,
    val signupsByDay: List<DailyCount>,
)

@Serializable
data class DailyCount(val date: String, val count: Long)

@Serializable
data class LessonStats(
    val byStatus: Map<LessonStatus, Long>,
    val scheduledInRange: Long,
    val completedInRange: Long,
    val cancelledInRange: Long,
    val byDay: List<LessonDay>,
    val topTeachers: List<TopTeacher>,
)

@Serializable
data class LessonDay(val date: String, val completed: Long, val cancelled: Long)

@Serializable
data class TopTeacher(val userId: String, val firstName: String, val lastName: String, val completed: Long)

@Serializable
data class HomeworkStats(
    val byStatus: Map<HomeworkStatus, Long>,
    val assignedInRange: Long,
    val submittedInRange: Long,
    val reviewedInRange: Long,
    val avgReviewHours: Double?,
)

@Serializable
data class PracticeStats(
    val vocabReviewsInRange: Long,
    val sentencesInRange: Long,
    val activeLearners7d: Long,
    val byDay: List<PracticeDay>,
)

@Serializable
data class PracticeDay(
    val date: String,
    val vocabReviews: Long,
    val sentences: Long,
    val homeworkSubmitted: Long,
    val lessonsCompleted: Long,
)

@Serializable
data class ContentStats(
    val materials: Long,
    val documents: Long,
    val vocabEntries: Long,
    val studentVocabByStatus: Map<StudentVocabStatus, Long>,
)

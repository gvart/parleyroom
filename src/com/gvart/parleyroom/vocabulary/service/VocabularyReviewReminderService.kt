package com.gvart.parleyroom.vocabulary.service

import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.user.data.TeacherStudentTable
import com.gvart.parleyroom.vocabulary.data.VocabStatus
import com.gvart.parleyroom.vocabulary.data.VocabularyWordTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.util.UUID

class VocabularyReviewReminderService(
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(VocabularyReviewReminderService::class.java)

    fun sendReminders(): Int {
        val now = OffsetDateTime.now()
        val dedupCutoff = now.minusHours(DEDUP_WINDOW_HOURS)

        val candidates = transaction {
            val dueStudents = VocabularyWordTable.selectAll()
                .where {
                    (VocabularyWordTable.status neq VocabStatus.LEARNED) and
                            VocabularyWordTable.nextReviewAt.lessEq(now)
                }
                .map { it[VocabularyWordTable.studentId].value }
                .toSet()

            if (dueStudents.isEmpty()) return@transaction emptyList<Pair<UUID, UUID>>()

            val recentlyReminded = NotificationTable.selectAll()
                .where {
                    (NotificationTable.userId inList dueStudents) and
                            (NotificationTable.type eq NotificationType.VOCAB_REVIEW_DUE) and
                            NotificationTable.createdAt.greater(dedupCutoff)
                }
                .map { it[NotificationTable.userId].value }
                .toSet()

            val remaining = dueStudents - recentlyReminded
            if (remaining.isEmpty()) return@transaction emptyList<Pair<UUID, UUID>>()

            val teacherByStudent = TeacherStudentTable.selectAll()
                .where { TeacherStudentTable.studentId inList remaining }
                .associate {
                    it[TeacherStudentTable.studentId].value to it[TeacherStudentTable.teacherId].value
                }

            remaining.mapNotNull { studentId ->
                teacherByStudent[studentId]?.let { teacherId -> studentId to teacherId }
            }
        }

        candidates.forEach { (studentId, teacherId) ->
            runCatching {
                notificationService.createNotification(
                    userId = studentId,
                    actorId = teacherId,
                    type = NotificationType.VOCAB_REVIEW_DUE,
                    referenceId = null,
                )
            }.onFailure { log.warn("Failed to create VOCAB_REVIEW_DUE notification for student {}", studentId, it) }
        }

        if (candidates.isNotEmpty()) {
            log.info("Sent {} vocabulary review reminders", candidates.size)
        }
        return candidates.size
    }

    companion object {
        const val DEDUP_WINDOW_HOURS = 24L
    }
}

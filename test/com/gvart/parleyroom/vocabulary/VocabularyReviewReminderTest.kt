package com.gvart.parleyroom.vocabulary

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.notification.service.NotificationSseManager
import com.gvart.parleyroom.vocabulary.data.VocabCategory
import com.gvart.parleyroom.vocabulary.data.VocabStatus
import com.gvart.parleyroom.vocabulary.data.VocabularyWordTable
import com.gvart.parleyroom.vocabulary.service.VocabularyReviewReminderService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class VocabularyReviewReminderTest : IntegrationTest() {

    private fun reminderService(): VocabularyReviewReminderService =
        VocabularyReviewReminderService(NotificationService(NotificationSseManager()))

    private fun seedDueWord(studentId: UUID, german: String = "Haus", nextReviewAt: OffsetDateTime = OffsetDateTime.now().minusHours(1)) {
        transaction {
            VocabularyWordTable.insertAndGetId {
                it[VocabularyWordTable.studentId] = studentId
                it[VocabularyWordTable.german] = german
                it[english] = "house"
                it[category] = VocabCategory.NOUN
                it[status] = VocabStatus.REVIEW
                it[VocabularyWordTable.nextReviewAt] = nextReviewAt
                it[addedAt] = OffsetDateTime.now().minusDays(7)
            }
        }
    }

    private fun reminderNotificationCount(studentId: UUID): Long = transaction {
        NotificationTable.selectAll()
            .where {
                (NotificationTable.userId eq studentId) and
                        (NotificationTable.type eq NotificationType.VOCAB_REVIEW_DUE)
            }
            .count()
    }

    @Test
    fun `sends reminder for student with due review`() = testApp {
        startApplication()
        val studentUuid = UUID.fromString(STUDENT_ID)
        seedDueWord(studentUuid)

        val sent = reminderService().sendReminders()

        assertEquals(1, sent)
        assertEquals(1, reminderNotificationCount(studentUuid))
    }

    @Test
    fun `does not remind when no words are due`() = testApp {
        startApplication()
        val studentUuid = UUID.fromString(STUDENT_ID)
        seedDueWord(studentUuid, nextReviewAt = OffsetDateTime.now().plusDays(3))

        val sent = reminderService().sendReminders()

        assertEquals(0, sent)
        assertEquals(0, reminderNotificationCount(studentUuid))
    }

    @Test
    fun `does not remind for LEARNED words`() = testApp {
        startApplication()
        val studentUuid = UUID.fromString(STUDENT_ID)
        seedDueWord(studentUuid)
        transaction {
            VocabularyWordTable.update({ VocabularyWordTable.studentId eq studentUuid }) {
                it[status] = VocabStatus.LEARNED
            }
        }

        val sent = reminderService().sendReminders()

        assertEquals(0, sent)
    }

    @Test
    fun `deduplicates within 24 hours`() = testApp {
        startApplication()
        val studentUuid = UUID.fromString(STUDENT_ID)
        seedDueWord(studentUuid)

        val service = reminderService()
        val first = service.sendReminders()
        val second = service.sendReminders()

        assertEquals(1, first)
        assertEquals(0, second)
        assertEquals(1, reminderNotificationCount(studentUuid))
    }

    @Test
    fun `reminds again after dedup window expires`() = testApp {
        startApplication()
        val studentUuid = UUID.fromString(STUDENT_ID)
        seedDueWord(studentUuid)

        val service = reminderService()
        service.sendReminders()

        // Backdate the earlier reminder past the 24h dedup window
        transaction {
            NotificationTable.update({
                (NotificationTable.userId eq studentUuid) and
                        (NotificationTable.type eq NotificationType.VOCAB_REVIEW_DUE)
            }) {
                it[createdAt] = OffsetDateTime.now().minusDays(2)
            }
        }

        val sentAgain = service.sendReminders()
        assertEquals(1, sentAgain)
        assertEquals(2, reminderNotificationCount(studentUuid))
    }

    @Test
    fun `skips student without teacher relationship`() = testApp {
        startApplication()
        val student2Uuid = UUID.fromString(STUDENT_2_ID)
        seedDueWord(student2Uuid)

        val sent = reminderService().sendReminders()

        assertEquals(0, sent)
        assertEquals(0, reminderNotificationCount(student2Uuid))
    }

    @Test
    fun `uses student's teacher as actor`() = testApp {
        startApplication()
        val studentUuid = UUID.fromString(STUDENT_ID)
        seedDueWord(studentUuid)

        reminderService().sendReminders()

        val actorId = transaction {
            NotificationTable.selectAll()
                .where {
                    (NotificationTable.userId eq studentUuid) and
                            (NotificationTable.type eq NotificationType.VOCAB_REVIEW_DUE)
                }
                .single()[NotificationTable.actorId].value
        }
        assertEquals(UUID.fromString(TEACHER_ID), actorId)
    }
}

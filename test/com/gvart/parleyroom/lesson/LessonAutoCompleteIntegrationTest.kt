package com.gvart.parleyroom.lesson

import com.gvart.parleyroom.IntegrationTest
import com.gvart.parleyroom.availability.service.AvailabilityValidator
import com.gvart.parleyroom.common.data.LessonType
import com.gvart.parleyroom.lesson.data.LessonEventTable
import com.gvart.parleyroom.lesson.data.LessonEventType
import com.gvart.parleyroom.lesson.data.LessonStatus
import com.gvart.parleyroom.lesson.data.LessonStudentStatus
import com.gvart.parleyroom.lesson.data.LessonStudentTable
import com.gvart.parleyroom.lesson.data.LessonTable
import com.gvart.parleyroom.lesson.service.LessonLifecycleService
import com.gvart.parleyroom.lesson.service.LessonSupport
import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.service.NotificationService
import com.gvart.parleyroom.notification.service.NotificationSseManager
import com.gvart.parleyroom.video.config.VideoConfig
import com.gvart.parleyroom.video.service.VideoTokenService
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.hours

class LessonAutoCompleteIntegrationTest : IntegrationTest() {

    private val teacher = UUID.fromString(TEACHER_ID)
    private val student = UUID.fromString(STUDENT_ID)
    private val grace = Duration.ofMinutes(60)

    private class RecordingVideoTokenService : VideoTokenService(
        VideoConfig("ws://127.0.0.1:1", "devkey", "devsecretdevsecretdevsecretdevsecret", 2.hours)
    ) {
        val deletedRooms = mutableListOf<String>()
        override fun deleteRoom(roomName: String) {
            deletedRooms += roomName
        }
    }

    private val video = RecordingVideoTokenService()

    private fun service() = LessonLifecycleService(
        NotificationService(NotificationSseManager()),
        video,
        LessonSupport(),
        AvailabilityValidator(),
    )

    private fun seedLesson(status: LessonStatus, scheduledAt: OffsetDateTime, durationMinutes: Int = 60): UUID =
        transaction {
            val now = OffsetDateTime.now()
            val id = LessonTable.insertAndGetId {
                it[title] = "Lektion"
                it[type] = LessonType.ONE_ON_ONE
                it[LessonTable.scheduledAt] = scheduledAt
                it[LessonTable.durationMinutes] = durationMinutes
                it[teacherId] = teacher
                it[LessonTable.status] = status
                it[topic] = "Alltag"
                it[createdBy] = teacher
                it[createdAt] = now
                it[updatedAt] = now
            }.value
            LessonStudentTable.insert {
                it[lessonId] = id
                it[studentId] = student
                it[LessonStudentTable.status] = LessonStudentStatus.CONFIRMED
            }
            id
        }

    private fun statusOf(lessonId: UUID): LessonStatus = transaction {
        LessonTable.selectAll().where { LessonTable.id eq lessonId }.single()[LessonTable.status]
    }

    private fun completedNotifications(lessonId: UUID): Long = transaction {
        NotificationTable.selectAll()
            .where {
                (NotificationTable.userId eq student) and
                        (NotificationTable.type eq NotificationType.LESSON_COMPLETED) and
                        (NotificationTable.referenceId eq lessonId)
            }
            .count()
    }

    @Test
    fun `completes stale in-progress lesson with notification, event and room deletion`() = testApp {
        startApplication()
        // Ended 90 minutes ago: 3h ago + 90 min duration.
        val lessonId = seedLesson(LessonStatus.IN_PROGRESS, OffsetDateTime.now().minusHours(3), durationMinutes = 90)

        val completed = service().autoCompleteStaleLessons(grace)

        assertEquals(1, completed)
        assertEquals(LessonStatus.COMPLETED, statusOf(lessonId))
        assertEquals(1, completedNotifications(lessonId))
        assertEquals(listOf("lesson-$lessonId"), video.deletedRooms)
        transaction {
            val row = LessonTable.selectAll().where { LessonTable.id eq lessonId }.single()
            assertNotNull(row[LessonTable.endedAt])
            assertEquals(teacher, row[LessonTable.updatedBy]?.value)
            val event = LessonEventTable.selectAll()
                .where { (LessonEventTable.lessonId eq lessonId) and (LessonEventTable.eventType eq LessonEventType.LESSON_COMPLETED) }
                .single()
            assertEquals(teacher, event[LessonEventTable.actorId].value)
        }
    }

    @Test
    fun `leaves in-progress lesson within grace period alone`() = testApp {
        startApplication()
        // Ended 30 minutes ago: within the 60 minute grace.
        val lessonId = seedLesson(LessonStatus.IN_PROGRESS, OffsetDateTime.now().minusMinutes(90))

        val completed = service().autoCompleteStaleLessons(grace)

        assertEquals(0, completed)
        assertEquals(LessonStatus.IN_PROGRESS, statusOf(lessonId))
        assertEquals(0, completedNotifications(lessonId))
        assertEquals(emptyList(), video.deletedRooms)
    }

    @Test
    fun `does not touch completed or cancelled lessons`() = testApp {
        startApplication()
        val longAgo = OffsetDateTime.now().minusDays(2)
        val done = seedLesson(LessonStatus.COMPLETED, longAgo)
        val cancelled = seedLesson(LessonStatus.CANCELLED, longAgo)

        val completed = service().autoCompleteStaleLessons(grace)

        assertEquals(0, completed)
        assertEquals(LessonStatus.COMPLETED, statusOf(done))
        assertEquals(LessonStatus.CANCELLED, statusOf(cancelled))
        assertEquals(0, completedNotifications(done) + completedNotifications(cancelled))
        assertEquals(emptyList(), video.deletedRooms)
    }

    @Test
    fun `is idempotent across repeated runs`() = testApp {
        startApplication()
        val lessonId = seedLesson(LessonStatus.IN_PROGRESS, OffsetDateTime.now().minusHours(5))
        val service = service()

        assertEquals(1, service.autoCompleteStaleLessons(grace))
        assertEquals(0, service.autoCompleteStaleLessons(grace))

        assertEquals(1, completedNotifications(lessonId))
        assertEquals(listOf("lesson-$lessonId"), video.deletedRooms)
    }
}

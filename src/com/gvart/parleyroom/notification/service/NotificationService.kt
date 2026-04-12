package com.gvart.parleyroom.notification.service

import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationActorResponse
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import com.gvart.parleyroom.notification.transfer.NotificationResponse
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class NotificationService(
    private val sseManager: NotificationSseManager,
) {

    fun getNotifications(principal: UserPrincipal, page: Int, pageSize: Int): NotificationPageResponse = transaction {
        val total = NotificationTable.selectAll()
            .where { NotificationTable.userId eq principal.id }
            .count()

        val notifications = NotificationTable
            .join(UserTable, JoinType.INNER, NotificationTable.actorId, UserTable.id)
            .selectAll()
            .where { NotificationTable.userId eq principal.id }
            .orderBy(NotificationTable.createdAt, SortOrder.DESC)
            .limit(pageSize)
            .offset(((page - 1) * pageSize).toLong())
            .map(::toResponse)

        NotificationPageResponse(
            notifications = notifications,
            total = total,
            page = page,
            pageSize = pageSize,
        )
    }

    fun markAsViewed(notificationIds: List<UUID>, principal: UserPrincipal): Int = transaction {
        NotificationTable.update({
            (NotificationTable.id inList notificationIds) and
                    (NotificationTable.userId eq principal.id)
        }) {
            it[viewed] = true
        }
    }

    fun createNotification(
        userId: UUID,
        actorId: UUID,
        type: NotificationType,
        referenceId: UUID? = null,
    ): NotificationResponse {
        val response = transaction {
            val now = OffsetDateTime.now()

            val id = NotificationTable.insertAndGetId {
                it[NotificationTable.userId] = userId
                it[NotificationTable.actorId] = actorId
                it[NotificationTable.type] = type
                it[NotificationTable.referenceId] = referenceId
                it[NotificationTable.createdAt] = now
            }

            NotificationTable
                .join(UserTable, JoinType.INNER, NotificationTable.actorId, UserTable.id)
                .selectAll()
                .where { NotificationTable.id eq id }
                .single()
                .let(::toResponse)
        }

        sseManager.emit(userId, response)

        return response
    }

    private fun toResponse(row: ResultRow) = NotificationResponse(
        id = row[NotificationTable.id].value.toString(),
        type = row[NotificationTable.type],
        referenceId = row[NotificationTable.referenceId]?.toString(),
        viewed = row[NotificationTable.viewed],
        actor = NotificationActorResponse(
            id = row[UserTable.id].value.toString(),
            firstName = row[UserTable.firstName],
            lastName = row[UserTable.lastName],
            role = row[UserTable.role],
        ),
        createdAt = row[NotificationTable.createdAt].toString(),
    )
}

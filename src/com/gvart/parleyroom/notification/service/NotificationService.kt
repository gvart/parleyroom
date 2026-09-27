package com.gvart.parleyroom.notification.service

import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationActorResponse
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import com.gvart.parleyroom.notification.transfer.NotificationResponse
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
            val id = NotificationTable.insertAndGetId {
                it[NotificationTable.userId] = userId
                it[NotificationTable.actorId] = actorId
                it[NotificationTable.type] = type
                it[NotificationTable.referenceId] = referenceId
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
        createdAt = row[NotificationTable.createdAt],
    )
}

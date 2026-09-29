package com.gvart.parleyroom.notification.service

import com.gvart.parleyroom.notification.data.NotificationTable
import com.gvart.parleyroom.notification.data.NotificationType
import com.gvart.parleyroom.notification.transfer.NotificationActorResponse
import com.gvart.parleyroom.notification.transfer.NotificationPageResponse
import com.gvart.parleyroom.notification.transfer.NotificationResponse
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.security.UserPrincipal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class NotificationService(
    private val sseManager: NotificationSseManager,
) {

    private val log = LoggerFactory.getLogger(NotificationService::class.java)
    private val delayedPushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun visibleTo(userId: UUID, now: OffsetDateTime): Op<Boolean> =
        (NotificationTable.userId eq userId) and
                (NotificationTable.deliverAfter.isNull() or (NotificationTable.deliverAfter lessEq now))

    fun getNotifications(principal: UserPrincipal, page: Int, pageSize: Int): NotificationPageResponse = transaction {
        val now = OffsetDateTime.now()
        val total = NotificationTable.selectAll()
            .where { visibleTo(principal.id, now) }
            .count()

        val notifications = NotificationTable
            .join(UserTable, JoinType.INNER, NotificationTable.actorId, UserTable.id)
            .selectAll()
            .where { visibleTo(principal.id, now) }
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

    /**
     * With [deliverAfter], the notification stays hidden from the list until then and is
     * pushed over SSE at that time, unless [withdrawUndelivered] removed it first. The push
     * is in-process, so a restart in between only loses the live push, not the notification.
     */
    fun createNotification(
        userId: UUID,
        actorId: UUID,
        type: NotificationType,
        referenceId: UUID? = null,
        deliverAfter: OffsetDateTime? = null,
    ): NotificationResponse {
        val response = transaction {
            val id = NotificationTable.insertAndGetId {
                it[NotificationTable.userId] = userId
                it[NotificationTable.actorId] = actorId
                it[NotificationTable.type] = type
                it[NotificationTable.referenceId] = referenceId
                it[NotificationTable.deliverAfter] = deliverAfter
            }
            findResponse(id.value)!!
        }

        if (deliverAfter == null) {
            sseManager.emit(userId, response)
        } else {
            delayedPushScope.launch {
                delay(Duration.between(OffsetDateTime.now(), deliverAfter).toMillis())
                runCatching { transaction { findResponse(UUID.fromString(response.id)) } }
                    .onSuccess { it?.let { sseManager.emit(userId, it) } }
                    .onFailure { log.warn("Delayed notification push failed", it) }
            }
        }

        return response
    }

    /** Deletes notifications of [type] about [referenceId] that are not delivered yet. */
    fun withdrawUndelivered(referenceId: UUID, type: NotificationType): Int = transaction {
        NotificationTable.deleteWhere {
            (NotificationTable.referenceId eq referenceId) and
                    (NotificationTable.type eq type) and
                    (NotificationTable.deliverAfter greater OffsetDateTime.now())
        }
    }

    fun shutdown() = delayedPushScope.cancel()

    private fun findResponse(id: UUID): NotificationResponse? =
        NotificationTable
            .join(UserTable, JoinType.INNER, NotificationTable.actorId, UserTable.id)
            .selectAll()
            .where { NotificationTable.id eq id }
            .singleOrNull()
            ?.let(::toResponse)

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

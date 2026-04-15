package com.gvart.parleyroom.notification.service

import com.gvart.parleyroom.notification.transfer.NotificationResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NotificationSseManager {

    private val log = LoggerFactory.getLogger(NotificationSseManager::class.java)

    private val connections = ConcurrentHashMap<UUID, MutableSet<MutableSharedFlow<NotificationResponse>>>()

    fun subscribe(userId: UUID): MutableSharedFlow<NotificationResponse> {
        val flow = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 64)
        connections.getOrPut(userId) { ConcurrentHashMap.newKeySet() }.add(flow)
        return flow
    }

    fun unsubscribe(userId: UUID, flow: MutableSharedFlow<NotificationResponse>) {
        val userFlows = connections[userId] ?: return
        userFlows.removeIf { it === flow }
        if (userFlows.isEmpty()) {
            connections.remove(userId)
        }
    }

    fun emit(userId: UUID, notification: NotificationResponse) {
        connections[userId]?.forEach { it.tryEmit(notification) }
    }

    fun shutdown() {
        val totalConnections = connections.values.sumOf { it.size }
        log.info("Shutting down SSE manager, closing {} connections for {} users", totalConnections, connections.size)
        connections.clear()
    }
}

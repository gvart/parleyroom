package com.gvart.parleyroom.notification.service

import com.gvart.parleyroom.notification.transfer.NotificationResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NotificationSseManager {

    private val log = LoggerFactory.getLogger(NotificationSseManager::class.java)

    private val connections = ConcurrentHashMap<UUID, MutableSet<MutableSharedFlow<String>>>()

    fun subscribe(userId: UUID): MutableSharedFlow<String> {
        val flow = MutableSharedFlow<String>(extraBufferCapacity = 64)
        connections.getOrPut(userId) { ConcurrentHashMap.newKeySet() }.add(flow)
        return flow
    }

    fun unsubscribe(userId: UUID, flow: MutableSharedFlow<String>) {
        val userFlows = connections[userId] ?: return
        userFlows.removeIf { it === flow }
        if (userFlows.isEmpty()) {
            connections.remove(userId)
        }
    }

    fun emit(userId: UUID, notification: NotificationResponse) = emit(userId, Json.encodeToString(notification))

    /** Pushes a ready `data:` payload, e.g. an event that is not a stored notification. */
    fun emit(userId: UUID, data: String) {
        connections[userId]?.forEach { it.tryEmit(data) }
    }

    fun shutdown() {
        val totalConnections = connections.values.sumOf { it.size }
        log.info("Shutting down SSE manager, closing {} connections for {} users", totalConnections, connections.size)
        connections.clear()
    }
}

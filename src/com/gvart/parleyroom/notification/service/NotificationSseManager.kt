package com.gvart.parleyroom.notification.service

import com.gvart.parleyroom.notification.transfer.NotificationResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NotificationSseManager {

    private val connections = ConcurrentHashMap<UUID, MutableSet<MutableSharedFlow<NotificationResponse>>>()

    fun subscribe(userId: UUID): SharedFlow<NotificationResponse> {
        val flow = MutableSharedFlow<NotificationResponse>(extraBufferCapacity = 64)
        connections.getOrPut(userId) { ConcurrentHashMap.newKeySet() }.add(flow)
        return flow.asSharedFlow()
    }

    fun unsubscribe(userId: UUID, flow: SharedFlow<NotificationResponse>) {
        val userFlows = connections[userId] ?: return
        userFlows.removeIf { it.asSharedFlow() == flow }
        if (userFlows.isEmpty()) {
            connections.remove(userId)
        }
    }

    fun emit(userId: UUID, notification: NotificationResponse) {
        connections[userId]?.forEach { it.tryEmit(notification) }
    }
}

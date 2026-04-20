package com.gvart.parleyroom.video.service

import com.gvart.parleyroom.video.config.VideoConfig
import com.gvart.parleyroom.video.transfer.VideoAccess
import com.gvart.parleyroom.video.transfer.VideoParticipantRole
import io.livekit.server.AccessToken
import io.livekit.server.CanPublish
import io.livekit.server.CanPublishData
import io.livekit.server.CanPublishSources
import io.livekit.server.CanSubscribe
import io.livekit.server.RoomJoin
import io.livekit.server.RoomName
import io.livekit.server.RoomServiceClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

class VideoTokenService(
    private val videoConfig: VideoConfig,
) {
    private val log = LoggerFactory.getLogger(VideoTokenService::class.java)

    /** LiveKit's server SDK talks HTTP/Twirp; the client URL is ws:// so we
     *  swap the scheme for the REST client. */
    private val roomService: RoomServiceClient = RoomServiceClient.createClient(
        host = httpBase(videoConfig.url),
        apiKey = videoConfig.apiKey,
        secret = videoConfig.apiSecret,
    )

    fun mintToken(
        roomName: String,
        identity: UUID,
        displayName: String,
        role: VideoParticipantRole,
        lessonId: UUID,
    ): VideoAccess {
        val publishSources = when (role) {
            VideoParticipantRole.TEACHER -> listOf("camera", "microphone", "screen_share", "screen_share_audio")
            VideoParticipantRole.STUDENT -> listOf("camera", "microphone")
        }

        val token = AccessToken(videoConfig.apiKey, videoConfig.apiSecret).apply {
            this.identity = identity.toString()
            name = displayName
            ttl = videoConfig.tokenTtl.inWholeMilliseconds
            metadata = Json.encodeToString(
                VideoTokenMetadata.serializer(),
                VideoTokenMetadata(role = role.name, lessonId = lessonId.toString()),
            )
            addGrants(
                RoomJoin(true),
                RoomName(roomName),
                CanPublish(true),
                CanSubscribe(true),
                CanPublishData(true),
                CanPublishSources(publishSources),
            )
        }

        return VideoAccess(
            roomName = roomName,
            accessToken = token.toJwt(),
            url = videoConfig.url,
        )
    }

    /**
     * Best-effort: tell LiveKit to close the given room, kicking every
     * connected participant. Used when the teacher finishes or cancels a
     * lesson so students aren't left dangling in a dead call.
     *
     * Fire-and-forget. If LiveKit is unreachable we log and move on — the
     * lesson status is the source of truth, and an empty room gets reaped
     * by LiveKit's idle timeout anyway.
     */
    fun deleteRoom(roomName: String) {
        runCatching {
            roomService.deleteRoom(roomName).execute()
        }.onSuccess {
            log.info("LiveKit room {} deleted", roomName)
        }.onFailure { err ->
            log.warn("Failed to delete LiveKit room {}: {}", roomName, err.message)
        }
    }

    @Serializable
    private data class VideoTokenMetadata(val role: String, val lessonId: String)

    companion object {
        /** LiveKit client URL is ws:// or wss://; the server SDK wants http(s). */
        internal fun httpBase(url: String): String {
            val trimmed = url.trim().removeSuffix("/")
            return when {
                trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
                trimmed.startsWith("ws://")  -> "http://"  + trimmed.removePrefix("ws://")
                else -> trimmed
            }
        }
    }
}

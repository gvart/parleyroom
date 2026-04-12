package com.gvart.parleyroom.video.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.gvart.parleyroom.video.config.VideoConfig
import com.gvart.parleyroom.video.transfer.VideoAccess
import com.gvart.parleyroom.video.transfer.VideoParticipantRole
import java.time.Instant
import java.util.UUID
import kotlin.time.toJavaDuration

class VideoTokenService(
    private val videoConfig: VideoConfig,
) {
    fun mintToken(
        roomName: String,
        identity: UUID,
        displayName: String,
        role: VideoParticipantRole,
        lessonId: UUID,
    ): VideoAccess {
        val now = Instant.now()
        val publishSources = when (role) {
            VideoParticipantRole.TEACHER -> listOf("camera", "microphone", "screen_share", "screen_share_audio")
            VideoParticipantRole.STUDENT -> listOf("camera", "microphone")
        }

        val videoGrant = mapOf(
            "room" to roomName,
            "roomJoin" to true,
            "roomCreate" to false,
            "canPublish" to true,
            "canSubscribe" to true,
            "canPublishData" to true,
            "canPublishSources" to publishSources,
        )

        val metadata = """{"role":"${role.name}","lessonId":"$lessonId"}"""

        val token = JWT.create()
            .withIssuer(videoConfig.apiKey)
            .withSubject(identity.toString())
            .withIssuedAt(now)
            .withNotBefore(now.minusSeconds(30))
            .withExpiresAt(now.plus(videoConfig.tokenTtl.toJavaDuration()))
            .withClaim("name", displayName)
            .withClaim("metadata", metadata)
            .withClaim("video", videoGrant)
            .sign(Algorithm.HMAC256(videoConfig.apiSecret))

        return VideoAccess(
            roomName = roomName,
            accessToken = token,
            url = videoConfig.url,
        )
    }
}

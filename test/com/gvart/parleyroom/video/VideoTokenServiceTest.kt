package com.gvart.parleyroom.video

import com.gvart.parleyroom.video.config.VideoConfig
import com.gvart.parleyroom.video.service.VideoTokenService
import com.gvart.parleyroom.video.transfer.VideoParticipantRole
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import java.util.UUID

/**
 * Protects two invariants the review flagged as easy to regress on:
 *  1. `deleteRoom` is fire-and-forget — it must not block the caller even
 *     when LiveKit is unreachable, or the teacher's /complete endpoint
 *     hangs until the SDK's HTTP timeout.
 *  2. `mintToken` works without a LiveKit server — the JWT is signed
 *     locally.
 */
class VideoTokenServiceTest {

    private val unreachableLiveKitConfig = VideoConfig(
        // Port 9 ("discard") is reserved and guaranteed closed. Any attempt
        // to open a TCP connection here fails fast with "connection refused".
        url = "ws://127.0.0.1:9",
        apiKey = "devkey",
        apiSecret = "devsecretdevsecretdevsecretdevsecret",
        tokenTtl = 2.hours,
    )

    @Test
    fun `deleteRoom returns immediately even when LiveKit is unreachable`() {
        val svc = VideoTokenService(unreachableLiveKitConfig)

        val elapsed = measureMillis {
            svc.deleteRoom("lesson-${UUID.randomUUID()}")
        }

        // Generous ceiling — the SDK call dispatches to Dispatchers.IO so
        // this should be well under 100ms in practice. 500ms cushions CI.
        assertTrue(
            elapsed < 500,
            "deleteRoom must not block on LiveKit HTTP — took ${elapsed}ms",
        )
    }

    @Test
    fun `mintToken signs locally without contacting LiveKit`() {
        val svc = VideoTokenService(unreachableLiveKitConfig)
        val lessonId = UUID.randomUUID()
        val access = svc.mintToken(
            roomName = "lesson-$lessonId",
            identity = UUID.randomUUID(),
            displayName = "Teacher",
            role = VideoParticipantRole.TEACHER,
            lessonId = lessonId,
        )

        assertTrue(access.accessToken.isNotBlank(), "token should be produced")
        assertTrue(access.roomName == "lesson-$lessonId", "room name round-trips")
    }
}

private inline fun measureMillis(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return (System.nanoTime() - start) / 1_000_000
}

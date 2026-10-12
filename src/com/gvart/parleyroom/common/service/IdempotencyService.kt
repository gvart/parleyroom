package com.gvart.parleyroom.common.service

import com.gvart.parleyroom.common.data.IdempotencyKeyTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Stores `Idempotency-Key`s per user for [TTL]. The (user, key) primary key decides concurrent
 * duplicates: one request claims the key, the others see it in progress or replay its response.
 */
class IdempotencyService {

    sealed interface Claim {
        /** The caller owns the key: run the request, then [complete] or [release]. */
        data object Acquired : Claim
        data class Replay(val status: Int, val body: String) : Claim
        /** The key was used for another route or payload. */
        data object Mismatch : Claim
        /** The first request with this key has not finished yet. */
        data object InProgress : Claim
    }

    fun claim(userId: UUID, key: String, method: String, path: String, requestHash: String): Claim = transaction {
        val now = OffsetDateTime.now()
        IdempotencyKeyTable.deleteWhere {
            (IdempotencyKeyTable.userId eq userId) and (IdempotencyKeyTable.key eq key) and (expiresAt lessEq now)
        }
        val inserted = IdempotencyKeyTable.insertIgnore {
            it[IdempotencyKeyTable.userId] = userId
            it[IdempotencyKeyTable.key] = key
            it[IdempotencyKeyTable.method] = method
            it[IdempotencyKeyTable.path] = path
            it[IdempotencyKeyTable.requestHash] = requestHash
            it[createdAt] = now
            it[expiresAt] = now.plus(TTL)
        }.insertedCount
        if (inserted == 1) return@transaction Claim.Acquired

        // Null only when the competing claim is not visible to this transaction yet.
        val row = IdempotencyKeyTable.selectAll()
            .where { (IdempotencyKeyTable.userId eq userId) and (IdempotencyKeyTable.key eq key) }
            .singleOrNull()
            ?: return@transaction Claim.InProgress
        val status = row[IdempotencyKeyTable.responseStatus]
        when {
            row[IdempotencyKeyTable.method] != method || row[IdempotencyKeyTable.path] != path ||
                    row[IdempotencyKeyTable.requestHash] != requestHash -> Claim.Mismatch
            status == null -> Claim.InProgress
            else -> Claim.Replay(status, row[IdempotencyKeyTable.responseBody].orEmpty())
        }
    }

    fun complete(userId: UUID, key: String, status: Int, body: String) = transaction {
        IdempotencyKeyTable.update({ (IdempotencyKeyTable.userId eq userId) and (IdempotencyKeyTable.key eq key) }) {
            it[responseStatus] = status
            it[responseBody] = body
        }
    }

    /** The request failed: forget the key so a retry runs again. */
    fun release(userId: UUID, key: String) = transaction {
        IdempotencyKeyTable.deleteWhere { (IdempotencyKeyTable.userId eq userId) and (IdempotencyKeyTable.key eq key) }
    }

    fun deleteExpired(): Int = transaction {
        IdempotencyKeyTable.deleteWhere { expiresAt lessEq OffsetDateTime.now() }
    }

    companion object {
        val TTL: Duration = Duration.ofHours(24)
    }
}

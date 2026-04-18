package com.gvart.parleyroom.user.service

import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.TelegramLinkResult
import com.gvart.parleyroom.user.transfer.TelegramLoginWidgetRequest
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class TelegramAuthService(
    private val verifier: TelegramInitDataVerifier,
    private val widgetVerifier: TelegramLoginWidgetVerifier,
    private val authenticationService: AuthenticationService,
) {
    /** Mini App sign-in: verify signature, look up user by telegram_id, issue JWT. */
    fun signInWithMiniApp(initDataRaw: String): AuthenticateResponse {
        val verified = verifier.verify(initDataRaw)
        return transaction {
            val user = UserTable.selectAll()
                .where { UserTable.telegramId eq verified.user.id }
                .singleOrNull()
                ?: throw NotFoundException("telegram_not_linked")
            authenticationService.issueTokens(user)
        }
    }

    /** Link (or re-link) the authenticated user's account to the Telegram id in a freshly verified initData. */
    fun linkTelegram(userId: UUID, initDataRaw: String): TelegramLinkResult {
        val verified = verifier.verify(initDataRaw)
        return performLink(userId, verified.user.id, verified.user.username)
    }

    /** Same link contract as [linkTelegram] but using a Telegram Login Widget payload from a plain browser. */
    fun linkTelegramFromWidget(userId: UUID, req: TelegramLoginWidgetRequest): TelegramLinkResult {
        val verified = widgetVerifier.verify(req)
        return performLink(userId, verified.telegramId, verified.telegramUsername)
    }

    private fun performLink(userId: UUID, telegramId: Long, telegramUsername: String?): TelegramLinkResult =
        transaction {
            val existingOwner = UserTable.selectAll()
                .where { UserTable.telegramId eq telegramId }
                .singleOrNull()
            if (existingOwner != null && existingOwner[UserTable.id].value != userId) {
                throw ConflictException("telegram_already_linked_to_another_account")
            }

            val updated = UserTable.update({ UserTable.id eq userId }) {
                it[UserTable.telegramId] = telegramId
                it[UserTable.telegramUsername] = telegramUsername
                it[updatedAt] = OffsetDateTime.now()
            }
            if (updated == 0) throw NotFoundException("User not found")

            TelegramLinkResult(telegramId = telegramId, telegramUsername = telegramUsername)
        }
}

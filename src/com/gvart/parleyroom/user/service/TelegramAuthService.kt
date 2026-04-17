package com.gvart.parleyroom.user.service

import com.gvart.parleyroom.common.transfer.exception.ConflictException
import com.gvart.parleyroom.common.transfer.exception.NotFoundException
import com.gvart.parleyroom.user.data.UserTable
import com.gvart.parleyroom.user.transfer.AuthenticateResponse
import com.gvart.parleyroom.user.transfer.TelegramLinkResult
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class TelegramAuthService(
    private val verifier: TelegramInitDataVerifier,
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
        val tgUser = verified.user
        return transaction {
            val existingOwner = UserTable.selectAll()
                .where { UserTable.telegramId eq tgUser.id }
                .singleOrNull()
            if (existingOwner != null && existingOwner[UserTable.id].value != userId) {
                throw ConflictException("telegram_already_linked_to_another_account")
            }

            val updated = UserTable.update({ UserTable.id eq userId }) {
                it[telegramId] = tgUser.id
                it[telegramUsername] = tgUser.username
                it[updatedAt] = OffsetDateTime.now()
            }
            if (updated == 0) throw NotFoundException("User not found")

            TelegramLinkResult(
                telegramId = tgUser.id,
                telegramUsername = tgUser.username,
            )
        }
    }
}

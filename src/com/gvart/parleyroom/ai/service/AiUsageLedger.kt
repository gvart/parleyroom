package com.gvart.parleyroom.ai.service

import com.gvart.parleyroom.ai.config.AiPricing
import com.gvart.parleyroom.ai.config.TokenCounts
import com.gvart.parleyroom.ai.data.AiUsageTable
import com.gvart.parleyroom.ai.data.AiUseCase
import com.gvart.parleyroom.ai.transfer.AiUsageByModel
import com.gvart.parleyroom.ai.transfer.AiUsageByUseCase
import com.gvart.parleyroom.ai.transfer.AiUsageByUser
import com.gvart.parleyroom.ai.transfer.AiUsageDay
import com.gvart.parleyroom.ai.transfer.AiUsageResponse
import com.gvart.parleyroom.ai.transfer.AiUsageTotals
import com.gvart.parleyroom.user.data.UserTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Who a model call is for: [model] is the routed model, [jobId] the generation job (null for synchronous calls). */
data class AiCall(val userId: UUID, val useCase: AiUseCase, val model: String, val jobId: UUID? = null)

/** The `ai_usage` ledger: one row per provider call, priced at insert. */
class AiUsageLedger(private val pricing: AiPricing) {

    private val log = LoggerFactory.getLogger(AiUsageLedger::class.java)

    /** Never throws: a lost ledger row must not fail the user's request. */
    fun record(call: AiCall, tokens: TokenCounts, errorCode: String? = null) {
        runCatching {
            val cost = pricing.cost(call.model, tokens)
            transaction {
                AiUsageTable.insert {
                    it[userId] = call.userId
                    it[useCase] = call.useCase
                    it[model] = call.model
                    it[jobId] = call.jobId
                    it[success] = errorCode == null
                    it[AiUsageTable.errorCode] = errorCode
                    it[inputTokens] = tokens.inputTokens
                    it[outputTokens] = tokens.outputTokens
                    it[cacheReadTokens] = tokens.cacheReadTokens
                    it[cacheWriteTokens] = tokens.cacheWriteTokens
                    it[costUsd] = cost
                    it[createdAt] = OffsetDateTime.now()
                }
            }
        }.onFailure { log.error("AI usage ledger write failed for {} {}", call.useCase, call.model, it) }
    }

    /** Aggregates the inclusive UTC day range in memory (a school's call volume is small). */
    fun report(from: LocalDate, to: LocalDate): AiUsageResponse = transaction {
        val start = from.atStartOfDay().atOffset(ZoneOffset.UTC)
        val end = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)
        val rows = AiUsageTable.join(UserTable, JoinType.INNER, AiUsageTable.userId, UserTable.id)
            .selectAll()
            .where { (AiUsageTable.createdAt greaterEq start) and (AiUsageTable.createdAt less end) }
            .toList()

        val byDay = rows.groupBy { it[AiUsageTable.createdAt].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate() }
        AiUsageResponse(
            from = from.toString(),
            to = to.toString(),
            totals = Stats.of(rows).let {
                AiUsageTotals(it.calls, it.failed, it.input, it.output, it.cacheRead, it.cacheWrite, it.cost.toDouble())
            },
            byDay = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { day ->
                val s = Stats.of(byDay[day].orEmpty())
                AiUsageDay(day.toString(), s.calls, s.failed, s.cost.toDouble(), s.input, s.output)
            }.toList(),
            byUseCase = rows.groupBy { it[AiUsageTable.useCase] }.map { (useCase, group) ->
                val s = Stats.of(group)
                AiUsageByUseCase(useCase, s.calls, s.failed, s.cost.toDouble(), s.input, s.output)
            }.sortedByDescending { it.costUsd },
            byModel = rows.groupBy { it[AiUsageTable.model] }.map { (model, group) ->
                val s = Stats.of(group)
                AiUsageByModel(model, s.calls, s.cost.toDouble())
            }.sortedByDescending { it.costUsd },
            byUser = rows.groupBy { it[AiUsageTable.userId].value }.map { (userId, group) ->
                val s = Stats.of(group)
                val user = group.first()
                AiUsageByUser(
                    userId = userId.toString(),
                    firstName = user[UserTable.firstName],
                    lastName = user[UserTable.lastName],
                    email = user[UserTable.email],
                    role = user[UserTable.role],
                    calls = s.calls,
                    failedCalls = s.failed,
                    costUsd = s.cost.toDouble(),
                    inputTokens = s.input,
                    outputTokens = s.output,
                    lastUsedAt = group.maxOf { it[AiUsageTable.createdAt] },
                )
            }.sortedByDescending { it.costUsd },
        )
    }

    private data class Stats(
        val calls: Int, val failed: Int, val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long, val cost: BigDecimal,
    ) {
        companion object {
            fun of(rows: List<ResultRow>) = Stats(
                calls = rows.size,
                failed = rows.count { !it[AiUsageTable.success] },
                input = rows.sumOf { it[AiUsageTable.inputTokens].toLong() },
                output = rows.sumOf { it[AiUsageTable.outputTokens].toLong() },
                cacheRead = rows.sumOf { it[AiUsageTable.cacheReadTokens].toLong() },
                cacheWrite = rows.sumOf { it[AiUsageTable.cacheWriteTokens].toLong() },
                cost = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row[AiUsageTable.costUsd] },
            )
        }
    }
}

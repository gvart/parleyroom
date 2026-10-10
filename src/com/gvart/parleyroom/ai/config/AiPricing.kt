package com.gvart.parleyroom.ai.config

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/** Token counts of one model call; [inputTokens] excludes the cached ones. */
data class TokenCounts(val inputTokens: Int = 0, val outputTokens: Int = 0, val cacheReadTokens: Int = 0, val cacheWriteTokens: Int = 0) {
    val promptTokens: Int get() = inputTokens + cacheReadTokens + cacheWriteTokens
}

/** USD per million tokens; [cacheWrite] is the 5-minute cache write. */
data class TokenPrices(val input: BigDecimal, val output: BigDecimal, val cacheRead: BigDecimal, val cacheWrite: BigDecimal)

/** [longContext] applies to the whole call once its prompt (input + cache read + cache write) exceeds [longContextThreshold]. */
data class ModelPricing(val base: TokenPrices, val longContextThreshold: Int? = null, val longContext: TokenPrices? = null) {
    fun prices(tokens: TokenCounts): TokenPrices =
        if (longContext != null && longContextThreshold != null && tokens.promptTokens > longContextThreshold) longContext else base
}

/** Prices per model id from `ai.pricing`; only these models can be routed to. */
class AiPricing(val models: Map<String, ModelPricing>) {

    private val log = LoggerFactory.getLogger(AiPricing::class.java)

    /** Unknown models cost 0 (with a warning) so the call is still recorded. */
    fun cost(model: String, tokens: TokenCounts): BigDecimal {
        val pricing = models[model] ?: run {
            log.warn("No price configured for AI model '{}': usage recorded at cost 0", model)
            return BigDecimal.ZERO.setScale(SCALE)
        }
        val prices = pricing.prices(tokens)
        return (prices.input * tokens.inputTokens.toBigDecimal() +
                prices.output * tokens.outputTokens.toBigDecimal() +
                prices.cacheRead * tokens.cacheReadTokens.toBigDecimal() +
                prices.cacheWrite * tokens.cacheWriteTokens.toBigDecimal())
            .divide(MILLION, SCALE, RoundingMode.HALF_UP)
    }

    companion object {
        private const val SCALE = 6
        private val MILLION = BigDecimal(1_000_000)
        private const val PREFIX = "ai.pricing."

        /** `ai.pricing.<model>.{input,output,cache_read,cache_write_5m}` plus optional `long_context.{threshold,…}`. */
        fun fromConfig(config: ApplicationConfig): AiPricing {
            val keys = config.keys().filter { it.startsWith(PREFIX) }
            val models = keys.map { it.removePrefix(PREFIX).substringBefore('.') }.distinct().associateWith { model ->
                fun prices(path: String) = TokenPrices(
                    input = config.property("$path.input").getString().toBigDecimal(),
                    output = config.property("$path.output").getString().toBigDecimal(),
                    cacheRead = config.property("$path.cache_read").getString().toBigDecimal(),
                    cacheWrite = config.property("$path.cache_write_5m").getString().toBigDecimal(),
                )
                val base = "$PREFIX$model"
                val long = config.propertyOrNull("$base.long_context.threshold")
                ModelPricing(
                    base = prices(base),
                    longContextThreshold = long?.getString()?.toInt(),
                    longContext = long?.let { prices("$base.long_context") },
                )
            }
            return AiPricing(models)
        }
    }
}

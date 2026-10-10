package com.gvart.parleyroom.ai

import com.gvart.parleyroom.ai.config.AiPricing
import com.gvart.parleyroom.ai.config.TokenCounts
import io.ktor.server.config.MapApplicationConfig
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ai.pricing block as in application.conf, for tests that need prices. */
val AI_PRICING_CONFIG = mapOf(
    "ai.pricing.claude-haiku-5-5.input" to "0.10",
    "ai.pricing.claude-haiku-5-5.output" to "0.50",
    "ai.pricing.claude-haiku-5-5.cache_read" to "0.01",
    "ai.pricing.claude-haiku-5-5.cache_write_5m" to "0.125",
    "ai.pricing.claude-haiku-5-5.long_context.threshold" to "100000",
    "ai.pricing.claude-haiku-5-5.long_context.input" to "0.50",
    "ai.pricing.claude-haiku-5-5.long_context.output" to "2.50",
    "ai.pricing.claude-haiku-5-5.long_context.cache_read" to "0.05",
    "ai.pricing.claude-haiku-5-5.long_context.cache_write_5m" to "0.625",
    "ai.pricing.claude-sonnet-5-5.input" to "2.00",
    "ai.pricing.claude-sonnet-5-5.output" to "10.00",
    "ai.pricing.claude-sonnet-5-5.cache_read" to "0.10",
    "ai.pricing.claude-sonnet-5-5.cache_write_5m" to "2.50",
)

class AiPricingTest {

    private val pricing = AiPricing.fromConfig(MapApplicationConfig(*AI_PRICING_CONFIG.toList().toTypedArray()))

    @Test
    fun `reads every priced model with its optional long-context tier`() {
        assertEquals(setOf("claude-haiku-5-5", "claude-sonnet-5-5"), pricing.models.keys)
        assertEquals(100_000, pricing.models["claude-haiku-5-5"]!!.longContextThreshold)
        assertNull(pricing.models["claude-sonnet-5-5"]!!.longContext)
    }

    @Test
    fun `base tier prices every token kind`() {
        // 10k * 0.10 + 2k * 0.50 + 50k * 0.01 + 4k * 0.125 = 1000 + 1000 + 500 + 500 = 3000 / 1M
        val cost = pricing.cost("claude-haiku-5-5", TokenCounts(10_000, 2_000, 50_000, 4_000))
        assertEquals(BigDecimal("0.003000"), cost)
    }

    @Test
    fun `the long-context tier starts above the threshold counting cached prompt tokens`() {
        // Prompt of exactly 100k: base, 40k * 0.10 + 60k * 0.01 + 1k * 0.50 = 5100 / 1M.
        assertEquals(BigDecimal("0.005100"), pricing.cost("claude-haiku-5-5", TokenCounts(40_000, 1_000, 60_000, 0)))
        // One more cached token flips the whole call: 40k * 0.50 + 60_001 * 0.05 + 1k * 2.50 = 25500.05 / 1M.
        val long = pricing.cost("claude-haiku-5-5", TokenCounts(40_000, 1_000, 60_001, 0))
        assertEquals(BigDecimal("0.025500"), long)
    }

    @Test
    fun `a model without a tier keeps base prices for long prompts`() {
        // 200k * 2.00 + 1k * 10.00 = 410_000 / 1M
        assertEquals(BigDecimal("0.410000"), pricing.cost("claude-sonnet-5-5", TokenCounts(200_000, 1_000)))
    }

    @Test
    fun `an unknown model costs nothing`() {
        assertEquals(BigDecimal("0.000000"), pricing.cost("claude-unknown", TokenCounts(1_000_000, 1_000_000)))
    }
}

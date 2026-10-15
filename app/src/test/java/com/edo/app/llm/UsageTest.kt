package com.edo.app.llm

import com.edo.app.data.Provider
import com.edo.app.data.UsageTotals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTest {
    private fun lines(raw: String) = flowOf(*raw.trimIndent().lines().toTypedArray())

    @Test
    fun anthropicReportsUsageFromStartAndDelta() = runTest {
        val raw = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":25,"cache_read_input_tokens":100,"cache_creation_input_tokens":10,"output_tokens":1}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}

            event: message_stop
            data: {"type":"message_stop"}

        """
        val events = AnthropicClient.parseAnthropicStream(lines(raw)).toList()
        val u = events.filterIsInstance<LlmEvent.Usage>().single().usage
        assertEquals(TokenUsage(inputTokens = 25, outputTokens = 42, cacheReadTokens = 100, cacheWriteTokens = 10), u)
        assertTrue(events.last() is LlmEvent.TurnEnd)
    }

    @Test
    fun openAiReportsUsageOnceAndSplitsCachedTokens() = runTest {
        val raw = """
            data: {"choices":[{"delta":{"content":"hi"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":1}}

            data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":100},"cost":0.0123}}

            data: [DONE]

        """
        val events = OpenAIClient.parseOpenAIStream(lines(raw)).toList()
        val u = events.filterIsInstance<LlmEvent.Usage>().single().usage
        assertEquals(TokenUsage(inputTokens = 20, outputTokens = 7, cacheReadTokens = 100, costUsd = 0.0123), u)
        assertTrue(events.last() is LlmEvent.TurnEnd)
    }

    @Test
    fun noUsageMeansNoEvent() = runTest {
        val raw = """
            data: {"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}

            data: [DONE]

        """
        assertTrue(OpenAIClient.parseOpenAIStream(lines(raw)).toList().none { it is LlmEvent.Usage })
    }

    private val table = PriceBook.parse(
        """{"sample_spec":{"max_tokens":"x"},
            "gpt-4o":{"input_cost_per_token":0.0000025,"output_cost_per_token":0.00001,"cache_read_input_token_cost":0.00000125},
            "claude-3-5-sonnet-20241022":{"input_cost_per_token":0.000003,"output_cost_per_token":0.000015,"cache_read_input_token_cost":3e-7,"cache_creation_input_token_cost":0.00000375},
            "claude-3-5-sonnet-20240620":{"input_cost_per_token":0.000003,"output_cost_per_token":0.000015},
            "openrouter/foo/bar":{"input_cost_per_token":1e-6,"output_cost_per_token":2e-6},
            "embedding-only":{"input_cost_per_token":1e-7}}"""
    )

    @Test
    fun parseKeepsOnlyFullyPricedModels() {
        assertEquals(setOf("gpt-4o", "claude-3-5-sonnet-20241022", "claude-3-5-sonnet-20240620", "openrouter/foo/bar"), table.keys)
    }

    @Test
    fun lookupFindsExactVendorPrefixedAndLatest() {
        assertNotNull(PriceBook.lookup(table, Provider.OpenAI, "GPT-4o"))
        assertNotNull(PriceBook.lookup(table, Provider.OpenRouter, "foo/bar"))
        assertNotNull(PriceBook.lookup(table, Provider.OpenRouter, "openai/gpt-4o"))
        // -latest resolves to the newest dated release
        assertEquals(3.75e-6, PriceBook.lookup(table, Provider.Anthropic, "claude-3-5-sonnet-latest")!!.cacheWrite!!, 1e-12)
        assertNull(PriceBook.lookup(table, Provider.OpenAI, "some-local-model"))
    }

    @Test
    fun costBillsCacheTokensAtTheirOwnPriceElseInputPrice() {
        val p = PriceBook.lookup(table, Provider.Anthropic, "claude-3-5-sonnet-20241022")!!
        val u = TokenUsage(inputTokens = 1000, outputTokens = 100, cacheReadTokens = 10_000, cacheWriteTokens = 2000)
        assertEquals(1000 * 3e-6 + 100 * 15e-6 + 10_000 * 3e-7 + 2000 * 3.75e-6, p.cost(u), 1e-12)
        val noCache = PriceBook.lookup(table, Provider.Anthropic, "claude-3-5-sonnet-20240620")!!
        assertEquals((1000 + 10_000 + 2000) * 3e-6 + 100 * 15e-6, noCache.cost(u), 1e-12)
    }

    @Test
    fun totalsFormatAndMarkPartialCost() {
        val t = UsageTotals(12_345, 1_200, 0.0421) + UsageTotals(10, 5, 0.0, partial = true)
        assertEquals("12.4k in · 1.2k out · $0.04+", t.label())
        assertEquals("500 in · 20 out", UsageTotals(500, 20).label())
        assertEquals("$0.0042", UsageTotals(1, 1, 0.0042).costLabel())
    }
}

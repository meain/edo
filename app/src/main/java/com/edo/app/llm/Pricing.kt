package com.edo.app.llm

import com.edo.app.data.Provider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** USD per token. Cache prices are null when the model has no separate cache pricing. */
data class Price(
    val input: Double,
    val output: Double,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
)

/** Cost of [u] at this price, with cache tokens billed at the plain input price when unpriced. */
fun Price.cost(u: TokenUsage): Double =
    u.inputTokens * input + u.outputTokens * output +
        u.cacheReadTokens * (cacheRead ?: input) + u.cacheWriteTokens * (cacheWrite ?: input)

/**
 * Model prices from LiteLLM's public price table, cached on disk and refreshed weekly. Used for
 * providers that report tokens but not money (Anthropic, OpenAI); OpenRouter reports its own cost.
 * Models missing from the table simply have no price, and the UI shows tokens only.
 */
class PriceBook(private val file: File, private val http: HttpClient) {
    @Volatile private var prices: Map<String, Price>? = null

    /** Price of [model], or null if unknown. */
    suspend fun price(provider: Provider, model: String): Price? {
        // The table is a megabyte or two of JSON; keep the first parse off the main thread.
        val table = prices ?: withContext(Dispatchers.Default) { load() }.also { prices = it }
        return lookup(table, provider, model)
    }

    /** Fetch a fresh table if the cached one is missing or older than a week. Safe to call often. */
    suspend fun refreshIfStale() = withContext(Dispatchers.IO) {
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS) return@withContext
        runCatching {
            val response = http.get(URL) { timeout { requestTimeoutMillis = 30_000 } }
            if (!response.status.isSuccess()) return@runCatching
            val text = response.bodyAsText()
            val parsed = parse(text)
            if (parsed.isEmpty()) return@runCatching
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
            prices = parsed
        }
    }

    private fun load(): Map<String, Price> =
        runCatching { if (file.exists()) parse(file.readText()) else emptyMap() }.getOrDefault(emptyMap())

    companion object {
        private const val URL =
            "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Keys are lower-cased model ids; entries without both token prices are dropped. */
        internal fun parse(json: String): Map<String, Price> {
            val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyMap()
            val out = HashMap<String, Price>()
            for ((key, value) in root) {
                val o = value as? JsonObject ?: continue
                val input = o["input_cost_per_token"]?.jsonPrimitive?.doubleOrNull ?: continue
                val output = o["output_cost_per_token"]?.jsonPrimitive?.doubleOrNull ?: continue
                out[key.lowercase()] = Price(
                    input, output,
                    cacheRead = o["cache_read_input_token_cost"]?.jsonPrimitive?.doubleOrNull,
                    cacheWrite = o["cache_creation_input_token_cost"]?.jsonPrimitive?.doubleOrNull,
                )
            }
            return out
        }

        /**
         * Tries the id as given, then LiteLLM's `openrouter/` form, then without a vendor prefix
         * (`anthropic/claude-x`), then, for `-latest` aliases, the newest dated release.
         */
        internal fun lookup(table: Map<String, Price>, provider: Provider, model: String): Price? {
            val m = model.trim().lowercase()
            if (m.isEmpty()) return null
            val candidates = buildList {
                if (provider == Provider.OpenRouter) add("openrouter/$m")
                add(m)
                if ('/' in m) add(m.substringAfterLast('/'))
            }
            for (c in candidates) table[c]?.let { return it }
            for (c in candidates) {
                if (!c.endsWith("-latest")) continue
                val base = c.removeSuffix("-latest")
                table.keys.filter { it.startsWith("$base-") && it.drop(base.length + 1).firstOrNull()?.isDigit() == true }
                    .maxOrNull()?.let { return table[it] }
            }
            return null
        }
    }
}

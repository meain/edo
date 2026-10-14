package com.edo.app.llm

import com.edo.app.data.OpenRouterOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json

const val OPENROUTER_BASE_URL = "https://openrouter.ai/api"

private fun String.slugs(): List<String> = split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Extra top-level request fields for OpenRouter's chat completions API: the `provider`
 * routing object, `models` fallbacks, `reasoning`, then the user's raw JSON merged on top.
 * Defaults are omitted so an untouched config sends exactly what a plain request would.
 */
fun OpenRouterOptions.requestExtras(model: String): JsonObject {
    val base = buildJsonObject {
        val provider = buildJsonObject {
            onlyProviders.slugs().takeIf { it.isNotEmpty() }?.let { l ->
                putJsonArray("only") { l.forEach { add(JsonPrimitive(it)) } }
            }
            ignoreProviders.slugs().takeIf { it.isNotEmpty() }?.let { l ->
                putJsonArray("ignore") { l.forEach { add(JsonPrimitive(it)) } }
            }
            if (sort.isNotBlank()) put("sort", sort)
            if (!allowFallbacks) put("allow_fallbacks", false)
            if (denyDataCollection) put("data_collection", "deny")
            if (requireParameters) put("require_parameters", true)
            if (zeroDataRetention) put("zdr", true)
        }
        if (provider.isNotEmpty()) put("provider", provider)
        fallbackModels.slugs().takeIf { it.isNotEmpty() }?.let { l ->
            putJsonArray("models") { (listOf(model) + l).forEach { add(JsonPrimitive(it)) } }
        }
        if (reasoningEffort.isNotBlank()) putJsonObject("reasoning") { put("effort", reasoningEffort) }
    }
    val custom = runCatching { Json.parseToJsonElement(extraJson).jsonObject }.getOrNull() ?: return base
    return JsonObject(base + custom)
}

/** True when [extraJson] is blank or a JSON object, for validating the settings field. */
fun isValidExtraJson(extraJson: String): Boolean =
    extraJson.isBlank() || runCatching { Json.parseToJsonElement(extraJson).jsonObject }.isSuccess

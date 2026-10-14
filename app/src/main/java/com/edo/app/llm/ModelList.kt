package com.edo.app.llm

import com.edo.app.data.Provider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Model ids the endpoint offers, via `GET {baseUrl}/v1/models` (both Anthropic and
 * OpenAI-compatible servers return `{"data":[{"id":…}]}`). Empty on any failure, since this
 * only feeds autocomplete and the field stays free-text.
 */
suspend fun fetchModelIds(http: HttpClient, provider: Provider, baseUrl: String, apiKey: String): List<String> =
    runCatching {
        val response = http.get(baseUrl.trimEnd('/') + "/v1/models") {
            when (provider) {
                Provider.Anthropic -> {
                    header("x-api-key", apiKey)
                    header("anthropic-version", "2023-06-01")
                    parameter("limit", 1000)
                }
                Provider.OpenAI -> if (apiKey.isNotEmpty()) header("Authorization", "Bearer $apiKey")
            }
        }
        if (!response.status.isSuccess()) return@runCatching emptyList()
        Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonArray
            .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

package com.edo.app.llm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AnthropicClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient,
    private val json: Json = DefaultJson,
    private val maxTokens: Int = 4096,
) : LlmClient {

    override fun stream(
        system: String?,
        conversation: List<ConvMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        val body = buildRequestJson(model, system, conversation, tools, maxTokens)
        val url = baseUrl.trimEnd('/') + "/v1/messages"

        http.preparePost(url) {
            headers {
                append("x-api-key", apiKey)
                append("anthropic-version", "2023-06-01")
                append("accept", "text/event-stream")
            }
            contentType(ContentType.Application.Json)
            setBody(body)
            timeout {
                requestTimeoutMillis = 5 * 60_000L
                socketTimeoutMillis = 5 * 60_000L
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val lines = readLines(channel)
            parseAnthropicStream(lines).collect { emit(it) }
        }
    }

    companion object {
        internal val DefaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        /** Read newline-delimited UTF-8 lines from a byte channel into a Flow. */
        internal fun readLines(channel: ByteReadChannel): Flow<String> = flow {
            while (true) {
                val line = channel.readUTF8Line() ?: break
                emit(line)
            }
        }

        /** Build the JSON body for /v1/messages. */
        internal fun buildRequestJson(
            model: String,
            system: String?,
            conversation: List<ConvMessage>,
            tools: List<ToolSpec>,
            maxTokens: Int,
        ): String {
            val obj = buildJsonObject {
                put("model", model)
                put("max_tokens", maxTokens)
                put("stream", true)
                if (!system.isNullOrBlank()) put("system", system)
                put("messages", buildMessagesArray(conversation))
                if (tools.isNotEmpty()) put("tools", buildToolsArray(tools))
            }
            return obj.toString()
        }

        internal fun buildMessagesArray(conversation: List<ConvMessage>): JsonArray {
            // Coalesce consecutive messages of the same role so the API never sees
            // two adjacent user or assistant turns (e.g. after stripping an orphaned
            // assistant tool_use on reload, the preceding user turn and the new user
            // message would otherwise be consecutive).
            val coalesced = mutableListOf<ConvMessage>()
            for (msg in conversation) {
                if (msg.role == Role.System) continue
                val last = coalesced.lastOrNull()
                if (last != null && last.role == msg.role) {
                    coalesced[coalesced.size - 1] = ConvMessage(last.role, last.blocks + msg.blocks)
                } else {
                    coalesced.add(msg)
                }
            }
            return buildJsonArray {
                for (msg in coalesced) {
                    addJsonObject {
                        put("role", if (msg.role == Role.User) "user" else "assistant")
                        put("content", buildContentArray(msg.blocks))
                    }
                }
            }
        }

        private fun buildContentArray(blocks: List<Block>): JsonArray = buildJsonArray {
            for (b in blocks) {
                when (b) {
                    is Block.Text -> addJsonObject {
                        put("type", "text")
                        put("text", b.text)
                    }
                    is Block.Image -> addJsonObject {
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", b.mediaType)
                            put("data", b.base64)
                        })
                    }
                    is Block.ToolUse -> addJsonObject {
                        put("type", "tool_use")
                        put("id", b.id)
                        put("name", b.name)
                        val parsed = runCatching { DefaultJson.parseToJsonElement(b.argsJson) }
                            .getOrElse { JsonObject(emptyMap()) }
                        put("input", parsed)
                    }
                    is Block.ToolResult -> addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", b.toolUseId)
                        put("content", b.content)
                        if (b.isError) put("is_error", true)
                    }
                }
            }
        }

        private fun buildToolsArray(tools: List<ToolSpec>): JsonArray = buildJsonArray {
            for (t in tools) {
                addJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    val schema = runCatching { DefaultJson.parseToJsonElement(t.parametersJson) }
                        .getOrElse { buildJsonObject { put("type", "object") } }
                    put("input_schema", schema)
                }
            }
        }

        /**
         * Parse Anthropic's SSE event stream into provider-neutral LlmEvents.
         * Pure function over a line flow — testable without HTTP.
         */
        fun parseAnthropicStream(lines: Flow<String>): Flow<LlmEvent> = flow {
            val toolIdsByIndex = mutableMapOf<Int, String>()
            var lastStopReason: String? = null

            parseSse(lines).collect { ev ->
                val payload = runCatching { DefaultJson.parseToJsonElement(ev.data).jsonObject }
                    .getOrNull() ?: return@collect

                when (ev.event) {
                    "content_block_start" -> {
                        val index = payload["index"]?.jsonPrimitive?.int ?: return@collect
                        val block = payload["content_block"]?.jsonObject ?: return@collect
                        when (block["type"]?.jsonPrimitive?.contentOrNull) {
                            "text" -> {
                                // emit any prefilled text
                                val t = block["text"]?.jsonPrimitive?.contentOrNull
                                if (!t.isNullOrEmpty()) emit(LlmEvent.TextDelta(t))
                            }
                            "tool_use" -> {
                                val id = block["id"]?.jsonPrimitive?.contentOrNull ?: return@collect
                                val name = block["name"]?.jsonPrimitive?.contentOrNull ?: return@collect
                                toolIdsByIndex[index] = id
                                emit(LlmEvent.ToolUseStart(id, name))
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val index = payload["index"]?.jsonPrimitive?.int ?: return@collect
                        val delta = payload["delta"]?.jsonObject ?: return@collect
                        when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> {
                                val t = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                if (t.isNotEmpty()) emit(LlmEvent.TextDelta(t))
                            }
                            "input_json_delta" -> {
                                val id = toolIdsByIndex[index] ?: return@collect
                                val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                emit(LlmEvent.ToolUseArgsDelta(id, partial))
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val index = payload["index"]?.jsonPrimitive?.int ?: return@collect
                        val id = toolIdsByIndex.remove(index)
                        if (id != null) emit(LlmEvent.ToolUseEnd(id))
                    }
                    "message_delta" -> {
                        val delta = payload["delta"]?.jsonObject
                        val reason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                        if (reason != null) lastStopReason = reason
                    }
                    "message_stop" -> {
                        emit(LlmEvent.TurnEnd(lastStopReason))
                    }
                    "error" -> {
                        val msg = payload["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: ev.data
                        emit(LlmEvent.Failure(msg))
                    }
                }
            }
        }
    }
}

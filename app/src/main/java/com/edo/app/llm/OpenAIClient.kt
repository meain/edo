package com.edo.app.llm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI-compatible Chat Completions client with streaming + tool calls.
 * Works with OpenAI, OpenRouter, Ollama (`/v1/chat/completions`), LM Studio, etc.
 */
class OpenAIClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val http: HttpClient,
    private val json: Json = AnthropicClient.DefaultJson,
) : LlmClient {

    override fun stream(
        system: String?,
        conversation: List<ConvMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        val body = buildRequestJson(model, system, conversation, tools)
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"

        http.preparePost(url) {
            headers {
                if (apiKey.isNotEmpty()) append("Authorization", "Bearer $apiKey")
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
            val lines = AnthropicClient.readLines(channel)
            parseOpenAIStream(lines).collect { emit(it) }
        }
    }

    companion object {
        internal fun buildRequestJson(
            model: String,
            system: String?,
            conversation: List<ConvMessage>,
            tools: List<ToolSpec>,
        ): String {
            val obj = buildJsonObject {
                put("model", model)
                put("stream", true)
                put("messages", buildMessagesArray(system, conversation))
                if (tools.isNotEmpty()) put("tools", buildToolsArray(tools))
            }
            return obj.toString()
        }

        internal fun buildMessagesArray(system: String?, conversation: List<ConvMessage>): JsonArray = buildJsonArray {
            if (!system.isNullOrBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
            }
            for (msg in conversation) {
                when (msg.role) {
                    Role.System -> addJsonObject {
                        put("role", "system")
                        put("content", textOnly(msg.blocks))
                    }
                    Role.User -> addUserMessages(msg.blocks)
                    Role.Assistant -> addAssistantMessage(msg.blocks)
                }
            }
        }

        private fun kotlinx.serialization.json.JsonArrayBuilder.addUserMessages(blocks: List<Block>) {
            // Split tool_result blocks into their own role-tool messages, as the
            // OpenAI protocol requires; the remaining user content (text+image)
            // becomes a single user message.
            val toolResults = blocks.filterIsInstance<Block.ToolResult>()
            for (tr in toolResults) {
                addJsonObject {
                    put("role", "tool")
                    put("tool_call_id", tr.toolUseId)
                    put("content", tr.content)
                }
            }
            val other = blocks.filter { it !is Block.ToolResult }
            if (other.isEmpty()) return

            val onlyText = other.all { it is Block.Text }
            addJsonObject {
                put("role", "user")
                if (onlyText) {
                    put("content", other.joinToString("") { (it as Block.Text).text })
                } else {
                    put("content", buildJsonArray {
                        for (b in other) {
                            when (b) {
                                is Block.Text -> addJsonObject {
                                    put("type", "text")
                                    put("text", b.text)
                                }
                                is Block.Image -> addJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:${b.mediaType};base64,${b.base64}")
                                    })
                                }
                                else -> Unit
                            }
                        }
                    })
                }
            }
        }

        private fun kotlinx.serialization.json.JsonArrayBuilder.addAssistantMessage(blocks: List<Block>) {
            val text = blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }
            val toolUses = blocks.filterIsInstance<Block.ToolUse>()
            addJsonObject {
                put("role", "assistant")
                if (text.isNotEmpty()) put("content", text) else put("content", "")
                if (toolUses.isNotEmpty()) {
                    put("tool_calls", buildJsonArray {
                        for (t in toolUses) {
                            addJsonObject {
                                put("id", t.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", t.name)
                                    put("arguments", t.argsJson)
                                })
                            }
                        }
                    })
                }
            }
        }

        private fun textOnly(blocks: List<Block>): String =
            blocks.filterIsInstance<Block.Text>().joinToString("") { it.text }

        private fun buildToolsArray(tools: List<ToolSpec>): JsonArray = buildJsonArray {
            for (t in tools) {
                addJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        val schema = runCatching { AnthropicClient.DefaultJson.parseToJsonElement(t.parametersJson) }
                            .getOrElse { buildJsonObject { put("type", "object") } }
                        put("parameters", schema)
                    })
                }
            }
        }

        /**
         * Parse OpenAI Chat Completions SSE stream into provider-neutral LlmEvents.
         * Pure function — testable without HTTP.
         */
        fun parseOpenAIStream(lines: Flow<String>): Flow<LlmEvent> = flow {
            val toolIdsByIndex = mutableMapOf<Int, String>()
            val toolStartedByIndex = mutableSetOf<Int>()
            var lastFinish: String? = null

            parseSse(lines).collect { ev ->
                val data = ev.data
                if (data.trim() == "[DONE]") {
                    emit(LlmEvent.TurnEnd(lastFinish))
                    return@collect
                }
                val payload = runCatching { AnthropicClient.DefaultJson.parseToJsonElement(data).jsonObject }
                    .getOrNull() ?: return@collect

                val err = payload["error"]
                if (err != null) {
                    val msg = err.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: data
                    emit(LlmEvent.Failure(msg))
                    return@collect
                }

                val choices = payload["choices"]?.jsonArray ?: return@collect
                if (choices.isEmpty()) return@collect
                val choice = choices[0].jsonObject
                val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finish != null) lastFinish = finish
                val delta = choice["delta"]?.jsonObject

                if (delta != null) {
                    val content = delta["content"]?.jsonPrimitive?.contentOrNull
                    if (!content.isNullOrEmpty()) emit(LlmEvent.TextDelta(content))

                    val toolCalls = delta["tool_calls"]?.jsonArray
                    if (toolCalls != null) {
                        for (tc in toolCalls) {
                            val obj = tc.jsonObject
                            val index = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
                            val idIncoming = obj["id"]?.jsonPrimitive?.contentOrNull
                            if (idIncoming != null) toolIdsByIndex[index] = idIncoming
                            val fn = obj["function"]?.jsonObject ?: continue
                            val name = fn["name"]?.jsonPrimitive?.contentOrNull
                            val argsChunk = fn["arguments"]?.jsonPrimitive?.contentOrNull

                            val id = toolIdsByIndex[index]
                            if (id != null && !toolStartedByIndex.contains(index) && !name.isNullOrEmpty()) {
                                toolStartedByIndex.add(index)
                                emit(LlmEvent.ToolUseStart(id, name))
                            }
                            if (id != null && !argsChunk.isNullOrEmpty()) {
                                emit(LlmEvent.ToolUseArgsDelta(id, argsChunk))
                            }
                        }
                    }
                }

                if (finish != null) {
                    for (idx in toolStartedByIndex) {
                        val id = toolIdsByIndex[idx] ?: continue
                        emit(LlmEvent.ToolUseEnd(id))
                    }
                    toolStartedByIndex.clear()
                    toolIdsByIndex.clear()
                }
            }
        }
    }
}

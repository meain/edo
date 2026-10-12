package com.edo.app.ui.chat

import com.edo.app.data.MessageEntity
import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Conversion between conversation messages and their persisted Room rows. */

internal fun uiToRow(msg: ConvMessage, threadId: Long): MessageEntity = MessageEntity(
    threadId = threadId,
    role = msg.role.name,
    contentJson = blocksToJson(msg.blocks).toString(),
)

internal fun rowToUi(row: MessageEntity): UiMessage? {
    val role = runCatching { Role.valueOf(row.role) }.getOrNull() ?: return null
    val arr = runCatching { Json.parseToJsonElement(row.contentJson).jsonArray }.getOrNull() ?: return null
    return UiMessage(row.id, role, jsonToBlocks(arr))
}

internal fun blocksToJson(blocks: List<Block>): JsonArray = buildJsonArray {
    for (b in blocks) {
        when (b) {
            is Block.Text -> addJsonObject { put("kind", "text"); put("text", b.text) }
            is Block.Image -> addJsonObject { put("kind", "image"); put("media_type", b.mediaType); put("data", b.base64) }
            is Block.ToolUse -> addJsonObject { put("kind", "tool_use"); put("id", b.id); put("name", b.name); put("args", b.argsJson) }
            is Block.ToolResult -> addJsonObject { put("kind", "tool_result"); put("id", b.toolUseId); put("content", b.content); put("is_error", b.isError) }
        }
    }
}

internal fun jsonToBlocks(arr: JsonArray): List<Block> = arr.mapNotNull { el ->
    val obj = el as? JsonObject ?: return@mapNotNull null
    when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
        "text" -> Block.Text(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
        "image" -> Block.Image(obj["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/png", obj["data"]?.jsonPrimitive?.contentOrNull ?: "")
        "tool_use" -> Block.ToolUse(obj["id"]?.jsonPrimitive?.contentOrNull ?: "", obj["name"]?.jsonPrimitive?.contentOrNull ?: "", obj["args"]?.jsonPrimitive?.contentOrNull ?: "{}")
        "tool_result" -> Block.ToolResult(obj["id"]?.jsonPrimitive?.contentOrNull ?: "", obj["content"]?.jsonPrimitive?.contentOrNull ?: "", obj["is_error"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false)
        else -> null
    }
}

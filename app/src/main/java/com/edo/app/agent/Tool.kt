package com.edo.app.agent

import com.edo.app.llm.ToolSpec

/** Outcome of a tool invocation. */
data class ToolResult(val content: String, val isError: Boolean = false)

/** A tool callable by the agent. Pure logic; UI approves before invocation. */
interface Tool {
    val spec: ToolSpec
    suspend fun invoke(argsJson: String): ToolResult
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> = tools.map { it.spec }

    fun get(name: String): Tool? = byName[name]
}

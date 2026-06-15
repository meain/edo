package com.edo.app.agent

import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.LlmClient
import com.edo.app.llm.LlmEvent
import com.edo.app.llm.Role
import com.edo.app.llm.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LlmClient that replays a script: each call returns the next prepared event sequence. */
private class ScriptedLlm(private val turns: List<List<LlmEvent>>) : LlmClient {
    private var i = 0
    override fun stream(
        system: String?,
        conversation: List<ConvMessage>,
        tools: List<ToolSpec>,
    ): Flow<LlmEvent> = flow {
        val turn = turns.getOrNull(i) ?: emptyList()
        i++
        for (e in turn) emit(e)
    }
}

class AgentTest {

    private fun echoTool() = object : Tool {
        override val spec = ToolSpec("echo", "echo back", """{"type":"object"}""")
        override suspend fun invoke(argsJson: String): ToolResult = ToolResult("ok:$argsJson")
    }

    @Test
    fun simpleTextResponseFinishes() = runTest {
        val script = listOf(
            listOf(
                LlmEvent.TextDelta("Hi "),
                LlmEvent.TextDelta("there"),
                LlmEvent.TurnEnd("end_turn"),
            ),
        )
        val agent = Agent(
            llm = ScriptedLlm(script),
            tools = ToolRegistry(listOf(echoTool())),
            gate = { _, _ -> ApprovalDecision.AllowOnce },
        )
        val conv = mutableListOf(ConvMessage(Role.User, listOf(Block.Text("hi"))))
        val events = agent.run(conv).toList()
        val text = events.filterIsInstance<AgentEvent.AssistantTextDelta>().joinToString("") { it.text }
        assertEquals("Hi there", text)
        assertTrue(events.last() is AgentEvent.Finished)
        // Conversation gained an assistant message
        assertEquals(2, conv.size)
        assertEquals(Role.Assistant, conv[1].role)
    }

    @Test
    fun toolCallApprovedExecutesAndContinues() = runTest {
        val script = listOf(
            listOf(
                LlmEvent.TextDelta("calling tool"),
                LlmEvent.ToolUseStart("t1", "echo"),
                LlmEvent.ToolUseArgsDelta("t1", "{\"k\":1}"),
                LlmEvent.ToolUseEnd("t1"),
                LlmEvent.TurnEnd("tool_use"),
            ),
            listOf(
                LlmEvent.TextDelta("done"),
                LlmEvent.TurnEnd("end_turn"),
            ),
        )
        val agent = Agent(
            llm = ScriptedLlm(script),
            tools = ToolRegistry(listOf(echoTool())),
            gate = { _, _ -> ApprovalDecision.AllowOnce },
        )
        val conv = mutableListOf(ConvMessage(Role.User, listOf(Block.Text("go"))))
        val events = agent.run(conv).toList()
        // Should have a ToolCallResult event with ok:{...}
        val result = events.filterIsInstance<AgentEvent.ToolCallResult>().single()
        assertEquals("ok:{\"k\":1}", result.result.content)
        // Conversation should now have: user, assistant(text+tool_use), user(tool_result), assistant(done)
        assertEquals(4, conv.size)
        assertEquals(Role.User, conv[0].role)
        assertEquals(Role.Assistant, conv[1].role)
        assertEquals(Role.User, conv[2].role)
        assertTrue(conv[2].blocks.any { it is Block.ToolResult })
        assertEquals(Role.Assistant, conv[3].role)
        // Finished as the last event
        assertTrue(events.last() is AgentEvent.Finished)
    }

    @Test
    fun toolCallDeniedYieldsErrorResultAndContinues() = runTest {
        val script = listOf(
            listOf(
                LlmEvent.ToolUseStart("t1", "echo"),
                LlmEvent.ToolUseArgsDelta("t1", "{}"),
                LlmEvent.ToolUseEnd("t1"),
                LlmEvent.TurnEnd("tool_use"),
            ),
            listOf(
                LlmEvent.TextDelta("ok"),
                LlmEvent.TurnEnd("end_turn"),
            ),
        )
        val agent = Agent(
            llm = ScriptedLlm(script),
            tools = ToolRegistry(listOf(echoTool())),
            gate = { _, _ -> ApprovalDecision.Deny },
        )
        val conv = mutableListOf(ConvMessage(Role.User, listOf(Block.Text("go"))))
        val events = agent.run(conv).toList()
        val result = events.filterIsInstance<AgentEvent.ToolCallResult>().single()
        assertTrue(result.result.isError)
        assertEquals("denied by user", result.result.content)
    }

    @Test
    fun allowAlwaysShortCircuitsLaterApprovalsForSameTool() = runTest {
        var calls = 0
        val gate = ApprovalGate { _, _ ->
            calls++
            ApprovalDecision.AllowAlwaysThisSession
        }
        // Two turns each producing a tool call to the same tool
        val script = listOf(
            listOf(
                LlmEvent.ToolUseStart("t1", "echo"),
                LlmEvent.ToolUseEnd("t1"),
                LlmEvent.TurnEnd("tool_use"),
            ),
            listOf(
                LlmEvent.ToolUseStart("t2", "echo"),
                LlmEvent.ToolUseEnd("t2"),
                LlmEvent.TurnEnd("tool_use"),
            ),
            listOf(
                LlmEvent.TextDelta("done"),
                LlmEvent.TurnEnd("end_turn"),
            ),
        )
        val agent = Agent(
            llm = ScriptedLlm(script),
            tools = ToolRegistry(listOf(echoTool())),
            gate = gate,
        )
        val conv = mutableListOf(ConvMessage(Role.User, listOf(Block.Text("go"))))
        agent.run(conv).toList()
        // Gate is only consulted once because the second call is auto-allowed
        assertEquals(1, calls)
    }

    @Test
    fun buildSystemPromptIncludesAgentsMd() {
        val ws = InMemoryWorkspace(mapOf("AGENTS.md" to "Rule: always be brief."))
        val prompt = buildSystemPrompt(ws)
        assertTrue(prompt.contains(Agent.DefaultSystemPrompt))
        assertTrue(prompt.contains("Rule: always be brief."))
        assertTrue(prompt.contains("AGENTS.md"))
    }

    @Test
    fun buildSystemPromptFallsBackToClaudeMd() {
        val ws = InMemoryWorkspace(mapOf("CLAUDE.md" to "use_jj"))
        val prompt = buildSystemPrompt(ws)
        assertTrue(prompt.contains("use_jj"))
    }

    @Test
    fun buildSystemPromptOmitsSectionWhenMissing() {
        val ws = InMemoryWorkspace()
        val prompt = buildSystemPrompt(ws)
        assertEquals(Agent.DefaultSystemPrompt, prompt)
    }

    @Test
    fun llmFailureFinishesAgentRun() = runTest {
        val script = listOf(
            listOf(LlmEvent.Failure("boom")),
        )
        val agent = Agent(
            llm = ScriptedLlm(script),
            tools = ToolRegistry(listOf(echoTool())),
            gate = { _, _ -> ApprovalDecision.AllowOnce },
        )
        val conv = mutableListOf(ConvMessage(Role.User, listOf(Block.Text("hi"))))
        val events = agent.run(conv).toList()
        assertTrue(events.any { it is AgentEvent.Failure })
        assertTrue(events.last() is AgentEvent.Finished)
    }
}

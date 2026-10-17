package com.edo.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolResultRepairTest {
    private fun use(id: String) = Block.ToolUse(id, "read", "{}")
    private fun result(id: String) = Block.ToolResult(id, "ok")

    @Test fun `valid history is untouched`() {
        val msgs = listOf(
            ConvMessage(Role.User, listOf(Block.Text("hi"))),
            ConvMessage(Role.Assistant, listOf(use("a"))),
            ConvMessage(Role.User, listOf(result("a"))),
        )
        assertEquals(msgs, withToolResultsRepaired(msgs))
    }

    @Test fun `unanswered tool_use mid-history gets a result`() {
        val msgs = listOf(
            ConvMessage(Role.Assistant, listOf(use("a"))),
            ConvMessage(Role.User, listOf(Block.Text("continue"))),
        )
        val fixed = withToolResultsRepaired(msgs)
        assertEquals(3, fixed.size)
        assertEquals("a", (fixed[1].blocks.single() as Block.ToolResult).toolUseId)
        assertSame(msgs[1], fixed[2])
    }

    @Test fun `partially answered results are topped up`() {
        val msgs = listOf(
            ConvMessage(Role.Assistant, listOf(use("a"), use("b"))),
            ConvMessage(Role.User, listOf(result("a"))),
        )
        val fixed = withToolResultsRepaired(msgs)
        assertEquals(2, fixed.size)
        assertEquals(listOf("a", "b"), fixed[1].blocks.map { (it as Block.ToolResult).toolUseId })
    }
}

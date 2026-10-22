package com.edo.app.ui.chat

import com.edo.app.llm.Block
import com.edo.app.llm.ConvMessage
import com.edo.app.llm.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionTest {

    @Test
    fun labelsTurnsAndKeepsOrder() {
        val text = compactionTranscript(
            listOf(
                ConvMessage(Role.User, listOf(Block.Text("list the files"))),
                ConvMessage(Role.Assistant, listOf(Block.ToolUse("t1", "read", """{"path":"."}"""))),
                ConvMessage(Role.User, listOf(Block.ToolResult("t1", "a.txt\nb.txt"))),
                ConvMessage(Role.Assistant, listOf(Block.Text("two files"))),
            )
        )
        assertEquals(
            "User: list the files\n\nAssistant called read({\"path\":\".\"})\n\nTool result: a.txt\nb.txt\n\nAssistant: two files",
            text,
        )
    }

    @Test
    fun trimsLongToolOutputButNotChatText() {
        val long = "x".repeat(5000)
        val text = compactionTranscript(
            listOf(
                ConvMessage(Role.User, listOf(Block.ToolResult("t1", long))),
                ConvMessage(Role.Assistant, listOf(Block.Text(long))),
            )
        )
        assertTrue(text.contains("…[trimmed]"))
        // The assistant's own words stay whole; only the tool result is cut.
        assertTrue(text.length > 5000 && text.length < 6000)
    }

    @Test
    fun attachedFilesAreTrimmedAndNamed() {
        val body = "y".repeat(4000)
        val text = compactionTranscript(
            listOf(ConvMessage(Role.User, listOf(Block.Text(attachedFileBlock("big.txt", body) + "summarise"))))
        )
        assertTrue(text.startsWith("User attached big.txt:"))
        assertFalse(text.contains("y".repeat(2000)))
        assertTrue(text.endsWith("User: summarise"))
    }
}

package com.edo.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailsTest {

    @Test
    fun runLuaCodeIsFlaggedForHighlightingAndKeptWhole() {
        val code = "print('hi')\n" + "x = 1\n".repeat(500)
        val args = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
        }.toString()
        val sections = toolSections("run_lua", args, "hi", isError = false)
        assertEquals(listOf("code", "Output"), sections.map { it.title })
        assertTrue(sections[0].lua)
        assertEquals(code, sections[0].text)
        assertTrue(sections[1].isOutput)
    }

    @Test
    fun otherToolsAreNotHighlightedAndNonStringArgsArePrettyPrinted() {
        val sections = toolSections("web_search", """{"query":"kotlin","limit":3,"tags":["a","b"]}""", null, false)
        assertEquals(listOf("query", "limit", "tags"), sections.map { it.title })
        assertFalse(sections.any { it.lua })
        assertEquals("kotlin", sections[0].text)
        assertEquals("3", sections[1].text)
        assertTrue(sections[2].text.contains("\n"))
    }

    @Test
    fun luaFileContentIsHighlightedByExtension() {
        val sections = toolSections("write_file", """{"path":"a/b.lua","content":"x = 1"}""", "ok", false)
        assertTrue(sections.first { it.title == "content" }.lua)
        assertFalse(sections.first { it.title == "path" }.lua)
    }

    @Test
    fun unparseableInputShowsAsIs_andErrorsAreLabelled() {
        val sections = toolSections("run_lua", """{"code":"print(""", "boom", isError = true)
        assertEquals(listOf("Input", "Error"), sections.map { it.title })
        assertEquals("""{"code":"print(""", sections[0].text)
        assertTrue(sections[1].isError)
    }
}

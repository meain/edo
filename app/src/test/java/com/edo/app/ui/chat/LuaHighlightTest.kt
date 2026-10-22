package com.edo.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LuaHighlightTest {

    /** Spans as (kind, text) so the expectations read like the source. */
    private fun lex(src: String) = luaSpans(src).map { it.kind to src.substring(it.start, it.end) }

    @Test
    fun picksOutKeywordsNumbersStringsAndBuiltins() {
        assertEquals(
            listOf(
                LuaToken.Keyword to "local",
                LuaToken.Number to "1",
                LuaToken.Comment to "-- hi",
                LuaToken.Builtin to "print",
                LuaToken.String to "'a'",
            ),
            lex("local x = 1 -- hi\nprint('a')"),
        )
    }

    @Test
    fun escapedQuoteStaysInsideTheString() {
        assertEquals(listOf(LuaToken.String to """"a\"b""""), lex(""""a\"b""""))
    }

    @Test
    fun longCommentsAndStringsSpanLines() {
        val src = "--[[ one\ntwo ]] x = [==[ a ]] b ]==]"
        assertEquals(
            listOf(LuaToken.Comment to "--[[ one\ntwo ]]", LuaToken.String to "[==[ a ]] b ]==]"),
            lex(src),
        )
    }

    @Test
    fun namesAfterFunctionAreMarkedAndHexAndExponentsAreNumbers() {
        assertEquals(
            listOf(
                LuaToken.Keyword to "function",
                LuaToken.FunctionName to "add",
                LuaToken.Number to "0xFF",
                LuaToken.Number to "1e-3",
            ),
            lex("function add(a, b) 0xFF 1e-3"),
        )
    }

    @Test
    fun unfinishedStringRunsToEndOfLine() {
        assertEquals(listOf(LuaToken.String to "'oops", LuaToken.Keyword to "end"), lex("'oops\nend"))
    }
}

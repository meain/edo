package com.edo.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString

internal enum class LuaToken { Keyword, String, Comment, Number, Builtin, FunctionName }

internal data class LuaSpan(val start: Int, val end: Int, val kind: LuaToken)

private val KEYWORDS = setOf(
    "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
    "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
)

/** Globals and libraries a run_lua program can reach, including the app's own `fs` and `http`. */
private val BUILTINS = setOf(
    "print", "pairs", "ipairs", "next", "type", "tostring", "tonumber", "select", "error", "assert",
    "pcall", "xpcall", "setmetatable", "getmetatable", "rawget", "rawset", "rawequal", "rawlen", "unpack",
    "string", "table", "math", "bit32", "coroutine", "fs", "http",
)

/** Level of the long bracket opening at [at] (`[[` is 0, `[=[` is 1, …), or -1 if there is none. */
private fun longBracketLevel(src: String, at: Int): Int {
    if (at >= src.length || src[at] != '[') return -1
    var i = at + 1
    while (i < src.length && src[i] == '=') i++
    return if (i < src.length && src[i] == '[') i - at - 1 else -1
}

/** Index just past the long bracket that closes the one opened at [open] with [level], or the end. */
private fun endOfLongBracket(src: String, open: Int, level: Int): Int {
    val close = "]" + "=".repeat(level) + "]"
    val e = src.indexOf(close, open + level + 2)
    return if (e < 0) src.length else e + close.length
}

/** Lexes Lua source into highlight spans. Forgiving: unfinished strings and comments run to the end. */
internal fun luaSpans(src: String): List<LuaSpan> {
    val out = ArrayList<LuaSpan>()
    var i = 0
    var afterFunction = false
    while (i < src.length) {
        val c = src[i]
        val start = i
        when {
            c == '-' && src.startsWith("--", i) -> {
                i += 2
                val level = longBracketLevel(src, i)
                i = if (level >= 0) endOfLongBracket(src, i, level) else src.indexOf('\n', i).let { if (it < 0) src.length else it }
                out += LuaSpan(start, i, LuaToken.Comment)
            }
            c == '"' || c == '\'' -> {
                i++
                while (i < src.length && src[i] != c && src[i] != '\n') {
                    if (src[i] == '\\' && i + 1 < src.length) i++
                    i++
                }
                if (i < src.length && src[i] == c) i++
                out += LuaSpan(start, i, LuaToken.String)
            }
            c == '[' && longBracketLevel(src, i) >= 0 -> {
                i = endOfLongBracket(src, i, longBracketLevel(src, i))
                out += LuaSpan(start, i, LuaToken.String)
            }
            c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                if (c == '0' && i + 1 < src.length && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                    i += 2
                    while (i < src.length && (src[i].isDigit() || src[i].lowercaseChar() in 'a'..'f')) i++
                } else {
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                        i++
                        if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                        while (i < src.length && src[i].isDigit()) i++
                    }
                }
                out += LuaSpan(start, i, LuaToken.Number)
            }
            c.isLetter() || c == '_' -> {
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                val word = src.substring(start, i)
                val kind = when {
                    word in KEYWORDS -> LuaToken.Keyword
                    afterFunction -> LuaToken.FunctionName
                    word in BUILTINS -> LuaToken.Builtin
                    else -> null
                }
                afterFunction = word == "function"
                if (kind != null) out += LuaSpan(start, i, kind)
            }
            else -> {
                // Whitespace between `function` and its name keeps the flag; anything else clears it.
                if (!c.isWhitespace()) afterFunction = false
                i++
            }
        }
    }
    return out
}

/** Colours for each token kind; supplied by the caller so the theme decides. */
internal class LuaPalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val builtin: Color,
    val functionName: Color,
)

internal fun highlightLua(src: String, palette: LuaPalette): AnnotatedString = buildAnnotatedString {
    append(src)
    for ((start, end, kind) in luaSpans(src)) {
        val style = when (kind) {
            LuaToken.Keyword -> SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold)
            LuaToken.String -> SpanStyle(color = palette.string)
            LuaToken.Comment -> SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)
            LuaToken.Number -> SpanStyle(color = palette.number)
            LuaToken.Builtin -> SpanStyle(color = palette.builtin)
            LuaToken.FunctionName -> SpanStyle(color = palette.functionName, fontWeight = FontWeight.Medium)
        }
        addStyle(style, start, end)
    }
}

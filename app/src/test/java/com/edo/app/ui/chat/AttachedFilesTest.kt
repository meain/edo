package com.edo.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachedFilesTest {

    @Test
    fun splitsFileAndMessage() {
        // Content with its own code fence must not end the block early.
        val content = "# hi\n\n```kotlin\nval x = 1\n```"
        val (files, rest) = splitAttachedFiles(attachedFileBlock("notes.md", content) + "summarise this")
        assertEquals(listOf(AttachedFile("notes.md", content.length)), files)
        assertEquals("summarise this", rest)
    }

    @Test
    fun plainTextIsUntouched() {
        val (files, rest) = splitAttachedFiles("just a message")
        assertEquals(emptyList<AttachedFile>(), files)
        assertEquals("just a message", rest)
    }

    @Test
    fun fileOnlyMessageHasEmptyRest() {
        val (files, rest) = splitAttachedFiles(attachedFileBlock("a.txt", "abc").trimEnd())
        assertEquals(listOf(AttachedFile("a.txt", 3)), files)
        assertEquals("", rest)
    }

    @Test
    fun quotesInNameDoNotBreakParsing() {
        val (files, _) = splitAttachedFiles(attachedFileBlock("we\"ird.txt", "x"))
        assertEquals("we'ird.txt", files.single().name)
    }
}

package com.edo.app.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionToolTest {
    private val args = """{"question":"Which one?","options":["a","b"]}"""

    @Test
    fun answerIsReturnedAsIs() = runTest {
        val result = AskUserQuestionTool { _, _ -> "b" }.invoke(args)
        assertFalse(result.isError)
        assertEquals("b", result.content)
    }

    @Test
    fun dismissedQuestionFailsTheToolCallInsteadOfHanging() = runTest {
        val result = AskUserQuestionTool { _, _ -> null }.invoke(args)
        assertTrue(result.isError)
        assertTrue(result.content.contains("dismissed"))
    }
}

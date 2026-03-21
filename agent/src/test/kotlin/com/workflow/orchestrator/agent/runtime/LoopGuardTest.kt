package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoopGuardTest {

    private lateinit var guard: LoopGuard

    @BeforeEach
    fun setup() {
        guard = LoopGuard()
    }

    // --- No injections when everything is normal ---

    @Test
    fun `no injections when tools are unique and no errors`() {
        val toolCalls = listOf(
            makeToolCall("read_file", """{"path": "/src/Main.kt"}"""),
        )
        val toolResults = listOf("tc-1" to false)

        val injections = guard.afterIteration(toolCalls, toolResults)

        assertTrue(injections.isEmpty())
    }

    @Test
    fun `no injections with null tool calls and null results`() {
        val injections = guard.afterIteration(null, null)
        assertTrue(injections.isEmpty())
    }

    // --- Loop detection moved to checkDoomLoop (pre-execution), afterIteration no longer detects loops ---

    @Test
    fun `afterIteration does not inject loop warnings (deferred to checkDoomLoop)`() {
        val toolCall = makeToolCall("read_file", """{"path": "/src/Main.kt"}""")

        // 3 identical calls via afterIteration should NOT trigger loop warning
        val result1 = guard.afterIteration(listOf(toolCall), listOf("tc-1" to false))
        val result2 = guard.afterIteration(listOf(toolCall), listOf("tc-2" to false))
        val result3 = guard.afterIteration(listOf(toolCall), listOf("tc-3" to false))

        assertFalse(result1.any { it.content?.contains("same arguments") == true })
        assertFalse(result2.any { it.content?.contains("same arguments") == true })
        assertFalse(result3.any { it.content?.contains("same arguments") == true })
    }

    // --- Error nudge injected after tool error ---

    @Test
    fun `error nudge injected when tool result has error`() {
        val toolCalls = listOf(makeToolCall("edit_file", """{"path": "/src/Main.kt"}"""))
        val toolResults = listOf("tc-1" to true) // isError = true

        val injections = guard.afterIteration(toolCalls, toolResults)

        val errorMsg = injections.find { it.content!!.contains("error") }
        assertNotNull(errorMsg)
        assertEquals("system", errorMsg!!.role)
        assertTrue(errorMsg.content!!.contains("Address this error"))
    }

    // --- No error nudge when tools succeed ---

    @Test
    fun `no error nudge when all tools succeed`() {
        val toolCalls = listOf(makeToolCall("read_file", """{"path": "/src/Main.kt"}"""))
        val toolResults = listOf("tc-1" to false) // isError = false

        val injections = guard.afterIteration(toolCalls, toolResults)

        assertFalse(injections.any { it.content?.contains("Address this error") == true })
    }

    @Test
    fun `no error nudge when tool results are null`() {
        val toolCalls = listOf(makeToolCall("read_file", """{"path": "/src/Main.kt"}"""))

        val injections = guard.afterIteration(toolCalls, null)

        assertFalse(injections.any { it.content?.contains("Address this error") == true })
    }

    // --- Instruction reminder at interval (every 4th iteration) ---

    @Test
    fun `instruction reminder injected at every 4th iteration`() {
        // Iterations 1-3: no reminder
        guard.afterIteration(null, null)
        guard.afterIteration(null, null)
        guard.afterIteration(null, null)

        // Iteration 4: reminder
        val result4 = guard.afterIteration(null, null)
        assertTrue(result4.any { it.content == LoopGuard.REMINDER_MESSAGE })

        // Iterations 5-7: no reminder
        guard.afterIteration(null, null)
        guard.afterIteration(null, null)
        guard.afterIteration(null, null)

        // Iteration 8: reminder again
        val result8 = guard.afterIteration(null, null)
        assertTrue(result8.any { it.content == LoopGuard.REMINDER_MESSAGE })
    }

    // --- No reminder at non-interval iterations ---

    @Test
    fun `no reminder at non-interval iterations`() {
        val result1 = guard.afterIteration(null, null) // iteration 1
        assertFalse(result1.any { it.content == LoopGuard.REMINDER_MESSAGE })

        val result2 = guard.afterIteration(null, null) // iteration 2
        assertFalse(result2.any { it.content == LoopGuard.REMINDER_MESSAGE })

        val result3 = guard.afterIteration(null, null) // iteration 3
        assertFalse(result3.any { it.content == LoopGuard.REMINDER_MESSAGE })
    }

    // --- beforeCompletion returns verification prompt when files modified ---

    @Test
    fun `beforeCompletion returns verification prompt when files were modified`() {
        guard.afterIteration(null, null, editedFiles = listOf("/src/Main.kt", "/src/App.kt"))

        val prompt = guard.beforeCompletion()

        assertNotNull(prompt)
        assertEquals("system", prompt!!.role)
        assertTrue(prompt.content!!.contains("/src/Main.kt"))
        assertTrue(prompt.content!!.contains("/src/App.kt"))
        assertTrue(prompt.content!!.contains("verify your changes"))
    }

    // --- beforeCompletion returns null when no files modified ---

    @Test
    fun `beforeCompletion returns null when no files were modified`() {
        guard.afterIteration(null, null) // no edited files

        val prompt = guard.beforeCompletion()

        assertNull(prompt)
    }

    // --- Reset clears all state ---

    @Test
    fun `reset clears all state`() {
        val toolCall = makeToolCall("read_file", """{"path": "/src/Main.kt"}""")

        // Build up state
        guard.afterIteration(listOf(toolCall), listOf("tc-1" to true), listOf("/src/Main.kt"))
        guard.afterIteration(listOf(toolCall), listOf("tc-2" to false))
        guard.afterIteration(null, null) // iteration 3
        guard.afterIteration(null, null) // iteration 4 - would be reminder

        // Reset
        guard.reset()

        // After reset: iteration count restarted, so iteration 1 doesn't trigger reminder
        val result1 = guard.afterIteration(listOf(toolCall), listOf("tc-5" to false))
        assertFalse(result1.any { it.content == LoopGuard.REMINDER_MESSAGE })

        // After reset: no modified files
        assertNull(guard.beforeCompletion())

        // After reset: doom loop state also cleared
        assertNull(guard.checkDoomLoop("search_code", """{"query": "test"}"""))
    }

    // --- Edited files tracked across iterations ---

    @Test
    fun `edited files tracked across multiple iterations`() {
        guard.afterIteration(null, null, editedFiles = listOf("/src/Main.kt"))
        guard.afterIteration(null, null, editedFiles = listOf("/src/App.kt"))
        guard.afterIteration(null, null, editedFiles = listOf("/src/Utils.kt"))

        val prompt = guard.beforeCompletion()

        assertNotNull(prompt)
        assertTrue(prompt!!.content!!.contains("/src/Main.kt"))
        assertTrue(prompt.content!!.contains("/src/App.kt"))
        assertTrue(prompt.content!!.contains("/src/Utils.kt"))
    }

    @Test
    fun `duplicate edited files are deduplicated`() {
        guard.afterIteration(null, null, editedFiles = listOf("/src/Main.kt"))
        guard.afterIteration(null, null, editedFiles = listOf("/src/Main.kt"))

        val prompt = guard.beforeCompletion()

        assertNotNull(prompt)
        // Should only appear once in the message
        val content = prompt!!.content!!
        val occurrences = content.split("/src/Main.kt").size - 1
        assertEquals(1, occurrences)
    }

    // --- Custom configuration ---

    @Test
    fun `custom reminder interval is respected`() {
        val customGuard = LoopGuard(reminderIntervalIterations = 2)

        // Iteration 1: no reminder
        val r1 = customGuard.afterIteration(null, null)
        assertFalse(r1.any { it.content == LoopGuard.REMINDER_MESSAGE })

        // Iteration 2: reminder
        val r2 = customGuard.afterIteration(null, null)
        assertTrue(r2.any { it.content == LoopGuard.REMINDER_MESSAGE })
    }

    @Test
    fun `doom loop threshold controls checkDoomLoop sensitivity`() {
        // Default DOOM_LOOP_THRESHOLD is 3
        val args = """{"query": "test"}"""
        assertNull(guard.checkDoomLoop("search_code", args))
        assertNull(guard.checkDoomLoop("search_code", args))
        val warning = guard.checkDoomLoop("search_code", args)
        assertNotNull(warning, "Expected doom loop warning after ${LoopGuard.DOOM_LOOP_THRESHOLD} identical calls")
    }

    // --- Multiple injections in a single iteration ---

    @Test
    fun `multiple injections can occur in same iteration`() {
        val toolCall = makeToolCall("read_file", """{"path": "/src/Main.kt"}""")

        // Build up to iteration 4 with an error
        guard.afterIteration(listOf(toolCall), listOf("tc-1" to false))
        guard.afterIteration(listOf(toolCall), listOf("tc-2" to false))
        guard.afterIteration(null, null) // iteration 3

        // Iteration 4: error + reminder interval
        val result = guard.afterIteration(
            listOf(toolCall),
            listOf("tc-3" to true)
        )

        // Should have error nudge + reminder (loop detection is now in checkDoomLoop, not afterIteration)
        assertTrue(result.any { it.content?.contains("Address this error") == true }, "Expected error nudge")
        assertTrue(result.any { it.content == LoopGuard.REMINDER_MESSAGE }, "Expected reminder")
    }

    // --- Different tool calls don't trigger doom loop ---

    @Test
    fun `different tool calls do not trigger doom loop detection`() {
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/A.kt"}"""))
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/B.kt"}"""))
        assertNull(guard.checkDoomLoop("edit_file", """{"path": "/src/A.kt"}"""))
    }

    // --- Doom loop detection (OpenCode pattern) ---

    @Test
    fun `checkDoomLoop returns null for first call`() {
        val result = guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        assertNull(result)
    }

    @Test
    fun `checkDoomLoop returns warning after 3 identical sequential calls`() {
        val args = """{"query": "TODO"}"""
        assertNull(guard.checkDoomLoop("search_code", args))
        assertNull(guard.checkDoomLoop("search_code", args))
        val warning = guard.checkDoomLoop("search_code", args)
        assertNotNull(warning)
        assertTrue(warning!!.contains("search_code"))
        assertTrue(warning.contains("${LoopGuard.DOOM_LOOP_THRESHOLD} times"))
    }

    @Test
    fun `checkDoomLoop does not trigger for different args`() {
        assertNull(guard.checkDoomLoop("search_code", """{"query": "A"}"""))
        assertNull(guard.checkDoomLoop("search_code", """{"query": "B"}"""))
        assertNull(guard.checkDoomLoop("search_code", """{"query": "C"}"""))
    }

    @Test
    fun `checkDoomLoop clears after triggering`() {
        val args = """{"query": "TODO"}"""
        guard.checkDoomLoop("search_code", args)
        guard.checkDoomLoop("search_code", args)
        val warning = guard.checkDoomLoop("search_code", args)
        assertNotNull(warning)

        // After triggering, list is cleared — next call should not trigger
        assertNull(guard.checkDoomLoop("search_code", args))
    }

    @Test
    fun `checkDoomLoop keeps list bounded to 20`() {
        // Add 25 unique calls
        for (i in 1..25) {
            guard.checkDoomLoop("tool_$i", "{}")
        }
        // No crash, no false positive
        assertNull(guard.checkDoomLoop("unique_tool", "{}"))
    }

    // --- File re-read tracking ---

    @Test
    fun `checkDoomLoop warns on re-reading same file`() {
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}"""))
        val warning = guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        assertNotNull(warning)
        assertTrue(warning!!.contains("already read"))
        assertTrue(warning.contains("/src/Main.kt"))
    }

    @Test
    fun `checkDoomLoop allows reading different files`() {
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/A.kt"}"""))
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/B.kt"}"""))
    }

    @Test
    fun `clearFileRead allows re-reading after edit`() {
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}"""))
        guard.clearFileRead("/src/Main.kt")
        // After clearing, re-read should not warn
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}"""))
    }

    @Test
    fun `reset clears doom loop and file read state`() {
        guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}""")
        guard.reset()
        // After reset, re-read should not warn
        assertNull(guard.checkDoomLoop("read_file", """{"path": "/src/Main.kt"}"""))
    }

    // --- Helper ---

    private var toolCallCounter = 0

    private fun makeToolCall(name: String, arguments: String): ToolCall {
        toolCallCounter++
        return ToolCall(
            id = "tc-$toolCallCounter",
            type = "function",
            function = FunctionCall(name = name, arguments = arguments)
        )
    }
}

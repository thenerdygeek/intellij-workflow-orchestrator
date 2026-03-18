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

    // --- Loop detection triggers after 3 identical tool calls ---

    @Test
    fun `loop detection triggers after 3 identical tool calls`() {
        val toolCall = makeToolCall("read_file", """{"path": "/src/Main.kt"}""")

        // First call - no injection
        val result1 = guard.afterIteration(listOf(toolCall), listOf("tc-1" to false))
        assertTrue(result1.isEmpty())

        // Second call - no injection
        val result2 = guard.afterIteration(listOf(toolCall), listOf("tc-2" to false))
        assertTrue(result2.isEmpty())

        // Third call - loop detected
        val result3 = guard.afterIteration(listOf(toolCall), listOf("tc-3" to false))
        assertTrue(result3.isNotEmpty())
        val loopMsg = result3.first()
        assertEquals("system", loopMsg.role)
        assertTrue(loopMsg.content!!.contains("read_file"))
        assertTrue(loopMsg.content!!.contains("same arguments"))
        assertTrue(loopMsg.content!!.contains("3 times"))
    }

    // --- Loop detection clears after redirect ---

    @Test
    fun `loop detection clears history after redirect`() {
        val toolCall = makeToolCall("read_file", """{"path": "/src/Main.kt"}""")

        // Trigger loop detection (3 calls)
        guard.afterIteration(listOf(toolCall), listOf("tc-1" to false))
        guard.afterIteration(listOf(toolCall), listOf("tc-2" to false))
        val result3 = guard.afterIteration(listOf(toolCall), listOf("tc-3" to false))
        assertTrue(result3.any { it.content!!.contains("same arguments") })

        // After redirect, same call should not trigger immediately
        val result4 = guard.afterIteration(listOf(toolCall), listOf("tc-4" to false))
        assertFalse(result4.any { it.content?.contains("same arguments") == true })
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

        // After reset: no loop detection (fresh history)
        val result1 = guard.afterIteration(listOf(toolCall), listOf("tc-5" to false))
        assertFalse(result1.any { it.content?.contains("same arguments") == true })

        // After reset: iteration count restarted, so iteration 1 doesn't trigger reminder
        assertFalse(result1.any { it.content == LoopGuard.REMINDER_MESSAGE })

        // After reset: no modified files
        assertNull(guard.beforeCompletion())
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
    fun `custom max duplicate calls is respected`() {
        val customGuard = LoopGuard(maxDuplicateToolCalls = 2)
        val toolCall = makeToolCall("read_file", """{"path": "/src/Main.kt"}""")

        // First call
        val r1 = customGuard.afterIteration(listOf(toolCall), listOf("tc-1" to false))
        assertTrue(r1.isEmpty())

        // Second call - triggers with max=2
        val r2 = customGuard.afterIteration(listOf(toolCall), listOf("tc-2" to false))
        assertTrue(r2.any { it.content?.contains("same arguments") == true })
    }

    // --- Multiple injections in a single iteration ---

    @Test
    fun `multiple injections can occur in same iteration`() {
        val toolCall = makeToolCall("read_file", """{"path": "/src/Main.kt"}""")

        // Build up to 3rd duplicate call at iteration 4 with an error
        guard.afterIteration(listOf(toolCall), listOf("tc-1" to false))
        guard.afterIteration(listOf(toolCall), listOf("tc-2" to false))
        guard.afterIteration(null, null) // iteration 3

        // Iteration 4: duplicate call + error + reminder interval
        val result = guard.afterIteration(
            listOf(toolCall),
            listOf("tc-3" to true)
        )

        // Should have loop redirect + error nudge + reminder
        assertTrue(result.any { it.content?.contains("same arguments") == true }, "Expected loop redirect")
        assertTrue(result.any { it.content?.contains("Address this error") == true }, "Expected error nudge")
        assertTrue(result.any { it.content == LoopGuard.REMINDER_MESSAGE }, "Expected reminder")
    }

    // --- Different tool calls don't trigger loop ---

    @Test
    fun `different tool calls do not trigger loop detection`() {
        val tc1 = makeToolCall("read_file", """{"path": "/src/A.kt"}""")
        val tc2 = makeToolCall("read_file", """{"path": "/src/B.kt"}""")
        val tc3 = makeToolCall("edit_file", """{"path": "/src/A.kt"}""")

        val r1 = guard.afterIteration(listOf(tc1), listOf("tc-1" to false))
        val r2 = guard.afterIteration(listOf(tc2), listOf("tc-2" to false))
        val r3 = guard.afterIteration(listOf(tc3), listOf("tc-3" to false))

        assertTrue(r1.isEmpty())
        assertTrue(r2.isEmpty())
        assertTrue(r3.isEmpty())
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

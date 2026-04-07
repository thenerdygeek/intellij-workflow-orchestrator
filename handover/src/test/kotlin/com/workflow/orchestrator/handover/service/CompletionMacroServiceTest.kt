package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.handover.model.MacroStep
import com.workflow.orchestrator.handover.model.MacroStepStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CompletionMacroServiceTest {

    private val service = CompletionMacroService()

    @Test
    fun `getDefaultSteps returns 4 chainable actions`() {
        val steps = service.getDefaultSteps()
        assertEquals(4, steps.size)
        assertEquals("copyright", steps[0].id)
        assertEquals("jira-comment", steps[1].id)
        assertEquals("jira-transition", steps[2].id)
        assertEquals("time-log", steps[3].id)
    }

    @Test
    fun `all default steps start as PENDING`() {
        val steps = service.getDefaultSteps()
        assertTrue(steps.all { it.status == MacroStepStatus.PENDING })
    }

    @Test
    fun `filterEnabledSteps removes disabled steps`() {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = false),
            MacroStep("c", "Step C", enabled = true)
        )
        val filtered = service.filterEnabledSteps(steps)
        assertEquals(2, filtered.size)
        assertEquals("a", filtered[0].id)
        assertEquals("c", filtered[1].id)
    }

    @Test
    fun `markStepStatus updates specific step`() {
        val steps = listOf(
            MacroStep("a", "Step A"),
            MacroStep("b", "Step B")
        )
        val updated = service.markStepStatus(steps, "a", MacroStepStatus.SUCCESS)
        assertEquals(MacroStepStatus.SUCCESS, updated[0].status)
        assertEquals(MacroStepStatus.PENDING, updated[1].status)
    }

    @Test
    fun `markStepStatus returns original list if id not found`() {
        val steps = listOf(MacroStep("a", "Step A"))
        val updated = service.markStepStatus(steps, "nonexistent", MacroStepStatus.FAILED)
        assertEquals(steps, updated)
    }

    @Test
    fun `executeMacro runs enabled steps and returns results`() = runTest {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = true)
        )
        val actions = mapOf<String, suspend () -> Boolean>(
            "a" to { true },
            "b" to { true }
        )

        val results = service.executeMacro(steps, actions)

        assertEquals(2, results.size)
        assertEquals(MacroStepStatus.SUCCESS, results[0].status)
        assertEquals(MacroStepStatus.SUCCESS, results[1].status)
    }

    @Test
    fun `executeMacro stops on failure and skips remaining`() = runTest {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = true),
            MacroStep("c", "Step C", enabled = true)
        )
        val actions = mapOf<String, suspend () -> Boolean>(
            "a" to { true },
            "b" to { false },
            "c" to { true }
        )

        val results = service.executeMacro(steps, actions)

        assertEquals(MacroStepStatus.SUCCESS, results[0].status)
        assertEquals(MacroStepStatus.FAILED, results[1].status)
        assertEquals(MacroStepStatus.SKIPPED, results[2].status)
    }

    @Test
    fun `executeMacro skips disabled steps`() = runTest {
        val steps = listOf(
            MacroStep("a", "Step A", enabled = true),
            MacroStep("b", "Step B", enabled = false),
            MacroStep("c", "Step C", enabled = true)
        )
        val actions = mapOf<String, suspend () -> Boolean>(
            "a" to { true },
            "c" to { true }
        )

        val results = service.executeMacro(steps, actions)

        assertEquals(MacroStepStatus.SUCCESS, results[0].status)
        assertEquals(MacroStepStatus.SKIPPED, results[1].status)
        assertEquals(MacroStepStatus.SUCCESS, results[2].status)
    }

    @Test
    fun `markRemainingSkipped skips all PENDING steps`() {
        val steps = listOf(
            MacroStep("a", "Step A", status = MacroStepStatus.SUCCESS),
            MacroStep("b", "Step B", status = MacroStepStatus.FAILED),
            MacroStep("c", "Step C", status = MacroStepStatus.PENDING),
            MacroStep("d", "Step D", status = MacroStepStatus.PENDING)
        )
        val updated = service.markRemainingSkipped(steps)
        assertEquals(MacroStepStatus.SUCCESS, updated[0].status)
        assertEquals(MacroStepStatus.FAILED, updated[1].status)
        assertEquals(MacroStepStatus.SKIPPED, updated[2].status)
        assertEquals(MacroStepStatus.SKIPPED, updated[3].status)
    }
}

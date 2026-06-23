package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentLoopWriteToolPropContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt").readText()

    @Test
    fun `plan-mode guard no longer references the WRITE_TOOLS name set`() {
        assertFalse(src.contains("toolName in WRITE_TOOLS"),
            "in-loop write classification must use tool.isMutating, not the WRITE_TOOLS set")
    }

    @Test
    fun `checkpoint + hook payload read isMutating`() {
        assertTrue(src.contains("tool.isMutating"),
            "AgentLoop must read tool.isMutating for write classification")
    }

    @Test
    fun `the WRITE_TOOLS name set and its usages are gone`() {
        // Target declaration + usage tokens specifically (the guard comment at ~1937 was
        // reworded in Task 6a, but stay robust against any future prose mention).
        assertFalse(src.contains("val WRITE_TOOLS"), "AgentLoop.WRITE_TOOLS declaration must be deleted")
        assertFalse(src.contains("in WRITE_TOOLS"), "no usage of the WRITE_TOOLS set may remain")
    }
}

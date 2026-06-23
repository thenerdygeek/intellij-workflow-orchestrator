package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentLoopHookExemptionContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt").readText()

    @Test
    fun `hook gates consult the per-tool isHookExempt property`() {
        assertTrue(src.contains("!tool.isHookExempt"),
            "AgentLoop hook gates must read tool.isHookExempt")
    }

    @Test
    fun `the HOOK_EXEMPT name set and its usages are gone`() {
        // Target the declaration + usage tokens specifically, so a stray lowercase prose
        // mention ("hook-exempt") can't false-fail the assertion.
        assertFalse(src.contains("val HOOK_EXEMPT"), "AgentLoop.HOOK_EXEMPT declaration must be deleted")
        assertFalse(src.contains("in HOOK_EXEMPT"), "no usage of the HOOK_EXEMPT set may remain")
    }
}

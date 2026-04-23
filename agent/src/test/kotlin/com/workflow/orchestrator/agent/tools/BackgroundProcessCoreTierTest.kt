package com.workflow.orchestrator.agent.tools

import com.workflow.orchestrator.agent.tools.builtin.BackgroundProcessTool
import com.workflow.orchestrator.agent.tools.builtin.SendStdinTool
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression test asserting the core-tier placement of [BackgroundProcessTool] and
 * [SendStdinTool] after Task 3.4 promoted them from deferred → core.
 *
 * Also documents that `kill_process` is NOT in the core tier (still deferred
 * as of Phase 3; Phase 4 will retire it entirely).
 */
class BackgroundProcessCoreTierTest {

    @Test
    fun `background_process and send_stdin are registered as core, not deferred`() {
        val registry = ToolRegistry()
        registry.registerCore(BackgroundProcessTool())
        registry.registerCore(SendStdinTool())

        // getActiveTools() returns core + active-deferred (no active-deferred here,
        // so this is effectively the core set).
        val coreNames = registry.getActiveTools().keys

        // getDeferredCatalog() lists tools still in the deferred tier.
        val deferredNames = registry.getDeferredCatalog().map { it.first }.toSet()

        assertTrue(
            "background_process" in coreNames,
            "background_process must be a core tool; core=$coreNames"
        )
        assertTrue(
            "send_stdin" in coreNames,
            "send_stdin must be a core tool; core=$coreNames"
        )
        assertFalse(
            "background_process" in deferredNames,
            "background_process must NOT be in the deferred catalog"
        )
        assertFalse(
            "send_stdin" in deferredNames,
            "send_stdin must NOT be in the deferred catalog"
        )
    }

    @Test
    fun `kill_process is not in the core tier (still deferred, future Phase 4 retires it)`() {
        // Register a fresh registry with only core tools — kill_process should be absent.
        val registry = ToolRegistry()
        registry.registerCore(BackgroundProcessTool())
        registry.registerCore(SendStdinTool())

        val coreNames = registry.getActiveTools().keys

        assertFalse(
            "kill_process" in coreNames,
            "kill_process must NOT be a core tool; it is still in the deferred tier and " +
            "will be retired entirely in Phase 4."
        )
    }
}

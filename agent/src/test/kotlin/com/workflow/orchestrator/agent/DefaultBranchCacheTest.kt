package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Source-text pin for the session-level DefaultBranchResolver cache (F-24).
 *
 * Confirms that:
 *  1. SessionRuntimeState.defaultBranch field is present and initially null
 *  2. The AgentService source guards the resolution launch with a null-check so it
 *     fires at most once per session rather than on every user message.
 */
class DefaultBranchCacheTest {

    @Test
    fun `SessionRuntimeState has defaultBranch AtomicReference initialised to null`() {
        // Use reflection so the test doesn't require an IntelliJ project or
        // application context — SessionRuntimeState is a private inner class.
        val cls = Class.forName(
            "com.workflow.orchestrator.agent.AgentService\$SessionRuntimeState"
        )
        val instance = cls.getDeclaredConstructors()[0].also { it.isAccessible = true }.newInstance()
        val field = cls.getDeclaredField("defaultBranch").also { it.isAccessible = true }
        val ref = field.get(instance) as java.util.concurrent.atomic.AtomicReference<*>
        assertNull(ref.get(), "defaultBranch must be null before first resolution (F-24)")
    }

    @Test
    fun `AgentService source guards branch resolution with null-check (source-text pin)`() {
        val src = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
            .takeIf { it.exists() }
            ?: java.io.File("agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        val text = src.readText()
        assertTrue(
            text.contains("if (resolvedDefaultBranch.get() == null)"),
            "Branch resolution launch must be guarded by a null-check to cache per session (F-24)"
        )
        assertTrue(
            text.contains("runtime.defaultBranch"),
            "resolvedDefaultBranch must come from the session-scoped runtime (F-24)"
        )
    }

    @Test
    fun `empty-string sentinel is converted back to null at call site (source-text pin)`() {
        val src = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
            .takeIf { it.exists() }
            ?: java.io.File("agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        val text = src.readText()
        assertTrue(
            text.contains(".ifEmpty { null }"),
            "empty-string sentinel must be converted to null before being passed to EnvironmentDetailsBuilder (F-24)"
        )
    }
}

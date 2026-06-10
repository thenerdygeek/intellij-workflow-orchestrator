package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceEffectiveWindowWiringTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test
    fun `constructs an EffectiveContextWindow from the shared catalog and settings snapshot`() {
        assertTrue(src.contains("EffectiveContextWindow("), "AgentService must construct EffectiveContextWindow")
        assertTrue(
            Regex("overrides\\s*=\\s*\\{[^}]*maxTokenOverridesSnapshot").containsMatchIn(src),
            "resolver overrides must read AgentSettings.maxTokenOverridesSnapshot()",
        )
    }

    @Test
    fun `injects the resolver into ContextManager construction`() {
        assertTrue(src.contains("effectiveContextWindow ="), "ContextManager(...) must receive effectiveContextWindow")
    }

    @Test
    fun `exposes the resolver to the controller`() {
        assertTrue(src.contains("fun getEffectiveContextWindow()"), "must expose getEffectiveContextWindow()")
    }
}

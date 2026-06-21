package com.workflow.orchestrator.agent.prompt

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvironmentDetailsBackgroundSectionTest {
    @Test fun `renders pending background tasks oldest-first`() {
        val s = EnvironmentDetailsBuilder.renderBackgroundTasks(
            listOf(Triple("tc1", "run_command", 5_000L), Triple("tc2", "search_code", 1_000L)),
        )
        assertTrue(s.contains("# Background tasks in progress"))
        assertTrue(s.contains("tc1"))
        assertTrue(s.contains("run_command"))
        assertTrue(s.contains("tc2"))
        // render preserves the registry's oldest-first input order (does not re-sort)
        assertTrue(s.indexOf("tc1") < s.indexOf("tc2"))
    }

    @Test fun `empty list renders nothing`() {
        assertTrue(EnvironmentDetailsBuilder.renderBackgroundTasks(emptyList()).isEmpty())
    }
}

package com.workflow.orchestrator.agent.delegation
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
class DelegationInboundRoutingTest {
    private fun src(): String {
        val d = System.getProperty("user.dir")
        val root = if (java.io.File("$d/src/main/kotlin").isDirectory) "$d/src/main/kotlin" else "$d/agent/src/main/kotlin"
        return java.io.File(root, "com/workflow/orchestrator/agent/delegation/DelegationInboundService.kt").readText()
    }
    @Test fun `handleConnect starts the session via the controller, not headless`() {
        val s = src()
        assertTrue(s.contains("AgentControllerRegistry"), "must obtain the controller")
        // Routed through the controller's startDelegatedSession — directly or via the
        // DelegatedSessionStarter adapter that wraps the resolved controller `c`.
        assertTrue(
            Regex("""\bc\.startDelegatedSession\(""").containsMatchIn(s) ||
                Regex("""controller[\w?.!]*\.startDelegatedSession\(""").containsMatchIn(s),
            "must start via the controller (directly or via the controller-backed starter adapter)",
        )
        // The starter seam is the controller in production: the adapter is built only inside the
        // controller-resolution branch, so the controller path remains the production route.
        assertTrue(s.contains("DelegatedSessionStarter"), "must route through the DelegatedSessionStarter seam")
        assertFalse(Regex("""agentService\.startDelegatedSession\(""").containsMatchIn(s), "must NOT call the headless AgentService path")
    }
}

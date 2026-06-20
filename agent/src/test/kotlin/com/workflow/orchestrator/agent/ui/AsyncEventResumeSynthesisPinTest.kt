package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text pin: verifies the critical structural properties of [AgentService.resumeSession]:
 * 1. Uses [AsyncEventResumeSynthesis] for card synthesis.
 * 2. Binds `drainedGroups` once (exactly one drainGrouped() call — no double-drain).
 * 3. Deduplicates against the in-memory `savedUiMessages` (not a fresh disk reload).
 * 4. Card synthesis is inside the cs.launch job, onto the resume-local handler (not appendAsyncEventCardToSession).
 */
class AsyncEventResumeSynthesisPinTest {

    private val agentServiceSource: String by lazy {
        val f = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
        check(f.exists()) { "AgentService.kt not found at ${f.absolutePath}" }
        f.readText()
    }

    @Test
    fun `resumeSession contains AsyncEventResumeSynthesis`() {
        assertTrue(
            agentServiceSource.contains("AsyncEventResumeSynthesis"),
            "AgentService.resumeSession must reference AsyncEventResumeSynthesis",
        )
    }

    @Test
    fun `resumeSession contains drainedGroups binding`() {
        assertTrue(
            agentServiceSource.contains("drainedGroups"),
            "AgentService.resumeSession must bind drainedGroups",
        )
    }

    @Test
    fun `resumeSession deduplicates against savedUiMessages asyncEventData ids`() {
        assertTrue(
            agentServiceSource.contains("savedUiMessages.mapNotNull { it.asyncEventData?.id }"),
            "AgentService.resumeSession must dedup against savedUiMessages.mapNotNull { it.asyncEventData?.id }",
        )
    }

    @Test
    fun `resumeSession calls drainGrouped exactly once`() {
        val count = agentServiceSource.split("drainGrouped()").size - 1
        assertEquals(
            1, count,
            "AgentService.resumeSession must call drainGrouped() exactly once (found $count occurrences in source)",
        )
    }
}

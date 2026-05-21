package com.workflow.orchestrator.agent.tools.runtime

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the diagnostic-log contract for the goals-blank guard. Source-text check
 * because the runtime path requires a full Maven/IntelliJ test fixture.
 */
class RunMavenGoalActionLoggingTest {

    private fun readSourceFile(): String {
        // Source path resolved relative to the working directory of test execution.
        // `:agent:test` runs from `/agent/`, so the source lives at `src/main/kotlin/...`.
        val candidate = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunMavenGoalAction.kt"
        )
        if (candidate.exists()) return candidate.readText()
        // Fallback for invocations from the repo root (some IDEs).
        val rooted = java.io.File(
            "agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RunMavenGoalAction.kt"
        )
        if (rooted.exists()) return rooted.readText()
        throw AssertionError(
            "RunMavenGoalAction.kt not found at either ${candidate.absolutePath} or ${rooted.absolutePath}"
        )
    }

    @Test
    fun `goals-blank guard logs received param keys`() {
        val source = readSourceFile()
        assertTrue(
            source.contains("goals is blank"),
            "Expected source to retain the existing 'goals is blank' message"
        )
        assertTrue(
            source.contains("LOG.warn") && source.contains("Received param keys"),
            "Expected source to log received param keys near the goals-blank guard."
        )
    }
}

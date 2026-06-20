package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class FindReferencesToolCancellableContractTest {
    private val src = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindReferencesTool.kt"
    ).readText()

    @Test
    fun `uses a cancellation-aware suspend read action, not executeSynchronously`() {
        assertTrue(
            src.contains("smartReadAction(") || src.contains("readAction {"),
            "find_references must use the suspend read-action API so coroutine cancel aborts the PSI walk",
        )
        assertFalse(
            src.contains("executeSynchronously()"),
            "the blocking executeSynchronously() read action must be removed",
        )
    }
}

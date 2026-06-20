package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RunInspectionsToolCancellableContractTest {
    private val src = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunInspectionsTool.kt"
    ).readText()

    @Test
    fun `walk is under a suspend read-action and polls cancellation`() {
        assertTrue(src.contains("smartReadAction("), "must use the suspend read-action API")
        assertFalse(src.contains("executeSynchronously()"), "blocking read action must be removed")
        assertTrue(
            src.contains("checkCanceled()"),
            "the PsiRecursiveElementWalkingVisitor walk must poll ProgressManager.checkCanceled() so a coroutine cancel aborts the CPU-bound walk",
        )
    }
}

package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SemanticDiagnosticsToolCancellableContractTest {
    private val src = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsTool.kt"
    ).readText()
    private val javaSrc = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ide/JavaKotlinProvider.kt"
    ).readText()
    private val pythonSrc = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonProvider.kt"
    ).readText()

    @Test
    fun `walk is under a suspend read-action and polls cancellation`() {
        assertTrue(src.contains("smartReadAction("), "must use the suspend read-action API")
        assertFalse(src.contains("executeSynchronously()"), "blocking read action must be removed")
        assertTrue(
            src.contains("checkCanceled()"),
            "provider walk must poll checkCanceled() so a coroutine cancel aborts the diagnostics walk",
        )
    }

    @Test
    fun `providers poll checkCanceled inside their diagnostics walk`() {
        // Note: asserting presence only — file-level contains is sufficient because
        // each provider has exactly one getDiagnostics walk that adds checkCanceled().
        assertTrue(
            javaSrc.contains("checkCanceled()"),
            "JavaKotlinProvider diagnostics walk must poll checkCanceled()",
        )
        assertTrue(
            pythonSrc.contains("checkCanceled()"),
            "PythonProvider diagnostics walk must poll checkCanceled()",
        )
    }
}

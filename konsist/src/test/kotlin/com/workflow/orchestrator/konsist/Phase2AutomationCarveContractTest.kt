package com.workflow.orchestrator.konsist

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// NOTE: use org.junit.jupiter.api.Assertions (NOT kotlin.test) — :konsist has no kotlin("test")
// dependency; every existing konsist test uses JUnit5 Assertions. assertFalse/assertTrue(condition,
// message) signatures match the calls below 1:1.

/**
 * Phase 2a carve contract: :automation no longer ships with Plugin A; it is bundled and
 * registered by Plugin B. These are source/text assertions (B's verifyPlugin is disabled, so
 * the silent-break surface needs an explicit pin). Built up across Tasks 1/2/4.
 */
class Phase2AutomationCarveContractTest {

    // Resolve repo root the same way PluginSplitEpContractTest does.
    private val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        while (!File(dir, "settings.gradle.kts").exists() && dir.parentFile != null) dir = dir.parentFile
        dir
    }

    private fun text(path: String): String = File(repoRoot, path).readText()

    @Test
    fun `A tool-window does not hard-code an Automation default tab`() {
        val src = text("core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt")
        assertFalse(
            src.contains("DefaultTab(\"Automation\""),
            "WorkflowToolWindowFactory must not declare Automation as a default tab; it is now an " +
                "extension-provided tab contributed by Plugin B.",
        )
    }
}

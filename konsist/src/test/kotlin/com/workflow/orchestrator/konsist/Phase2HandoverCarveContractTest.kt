package com.workflow.orchestrator.konsist

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase 2b carve contract: :handover no longer ships with Plugin A; it is bundled and registered
 * by Plugin B. The generic CopyrightFixService year-logic STAYS in A (:core). Built across Tasks 1/3/4.
 */
class Phase2HandoverCarveContractTest {

    private val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        while (!File(dir, "settings.gradle.kts").exists() && dir.parentFile != null) dir = dir.parentFile
        dir
    }

    private fun text(path: String): String = File(repoRoot, path).readText()

    @Test
    fun `A tool-window does not hard-code a Handover default tab`() {
        val src = text("core/src/main/kotlin/com/workflow/orchestrator/core/toolwindow/WorkflowToolWindowFactory.kt")
        assertFalse(
            src.contains("DefaultTab(\"Handover\""),
            "WorkflowToolWindowFactory must not declare Handover as a default tab; it is now an " +
                "extension-provided tab contributed by Plugin B.",
        )
    }
}

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

    @Test
    fun `A plugin_xml registers no automation classes`() {
        val xml = text("src/main/resources/META-INF/plugin.xml")
        assertFalse(
            xml.contains("com.workflow.orchestrator.automation"),
            "Plugin A's plugin.xml must not register any :automation class after the carve.",
        )
    }

    @Test
    fun `A build does not bundle the automation module`() {
        val build = text("build.gradle.kts")
        assertFalse(
            build.contains("implementation(project(\":automation\")"),
            "Plugin A's build must not bundle (implementation) the :automation project after the carve. " +
                "kover(project(\":automation\")) is intentionally retained for repo-wide coverage aggregation.",
        )
    }

    @Test
    fun `B plugin_xml bundles automation registrations`() {
        val xml = text("plugin-b/src/main/resources/META-INF/plugin.xml")
        assertTrue(
            xml.contains("com.workflow.orchestrator.automation.ui.AutomationTabProvider"),
            "Plugin B must register the Automation tab provider.",
        )
        assertTrue(
            xml.contains("com.workflow.orchestrator.automation.settings.AutomationConfigurable"),
            "Plugin B must register the Automation settings page.",
        )
        assertTrue(
            xml.contains("com.workflow.orchestrator.automation.service.QueueService"),
            "Plugin B must register the Automation QueueService.",
        )
        assertTrue(
            xml.contains("com.workflow.orchestrator.automation.service.AutomationSettingsService"),
            "Plugin B must register the app-level AutomationSettingsService.",
        )
        assertFalse(
            xml.contains("ConflictDetectorService"),
            "The dangling ConflictDetectorService must NOT be registered in B (class does not exist).",
        )
    }

    @Test
    fun `B build bundles the automation module`() {
        val build = text("plugin-b/build.gradle.kts")
        assertTrue(
            build.contains("project(\":automation\")"),
            "Plugin B's build must bundle the :automation module.",
        )
    }

    @Test
    fun `no A-resident src-main Kotlin file references the B-only workflow-automation notification group`() {
        val aModules = listOf("core", "jira", "bamboo", "sonar", "pullrequest", "handover", "agent", "web", "document")
        val hits = mutableListOf<String>()
        for (module in aModules) {
            val srcMain = File(repoRoot, "$module/src/main")
            if (!srcMain.exists()) continue
            srcMain.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { file ->
                    if (file.readText().contains("\"workflow.automation\"")) {
                        hits.add("$module/${file.name}")
                    }
                }
        }
        assertTrue(
            hits.isEmpty(),
            "A-resident src/main Kotlin files must not reference the B-only \"workflow.automation\" " +
                "notification group. Offending files: $hits",
        )
    }

    @Test
    fun `root build still aggregates automation module in kover`() {
        val build = text("build.gradle.kts")
        assertTrue(
            build.contains("kover(project(\":automation\"))"),
            "root build.gradle.kts must retain kover(project(\":automation\")) for repo-wide coverage " +
                "aggregation; the carve intentionally kept :automation in the coverage roll-up.",
        )
    }
}

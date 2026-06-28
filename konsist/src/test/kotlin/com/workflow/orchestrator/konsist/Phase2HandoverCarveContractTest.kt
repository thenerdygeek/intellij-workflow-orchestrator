package com.workflow.orchestrator.konsist

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `A plugin_xml registers no handover classes`() {
        val xml = text("src/main/resources/META-INF/plugin.xml")
        // CopyrightFixService is now core.copyright (kept in A) and PrService is core.bitbucket (kept in A);
        // neither matches the handover package. Nothing under com.workflow.orchestrator.handover may remain.
        assertFalse(
            xml.contains("com.workflow.orchestrator.handover"),
            "Plugin A's plugin.xml must not register any :handover class after the carve.",
        )
        assertFalse(
            xml.contains("PreReviewService"),
            "The dead PreReviewService registration must be dropped (class does not exist).",
        )
    }

    @Test
    fun `A build does not bundle the handover module`() {
        val build = text("build.gradle.kts")
        assertFalse(
            build.contains("implementation(project(\":handover\")"),
            "Plugin A's build must not bundle the :handover project (kover(:handover) is retained).",
        )
    }

    @Test
    fun `A keeps the generic CopyrightFixService and PrService`() {
        val xml = text("src/main/resources/META-INF/plugin.xml")
        assertTrue(
            xml.contains("com.workflow.orchestrator.core.copyright.CopyrightFixService"),
            "Generic CopyrightFixService stays registered in A (moved to :core).",
        )
        assertTrue(
            xml.contains("com.workflow.orchestrator.core.bitbucket.PrService"),
            "Generic PrService (consumed by :pullrequest) stays registered in A.",
        )
    }

    @Test
    fun `B plugin_xml bundles handover registrations`() {
        val xml = text("plugin-b/src/main/resources/META-INF/plugin.xml")
        assertTrue(
            xml.contains("com.workflow.orchestrator.handover.ui.HandoverTabProvider"),
            "Plugin B must register the Handover tab provider.",
        )
        assertTrue(
            xml.contains("com.workflow.orchestrator.handover.settings.HandoverConfigurable"),
            "Plugin B must register the Handover settings page (moved to :handover).",
        )
        assertTrue(
            xml.contains("com.workflow.orchestrator.handover.service.HandoverStateService"),
            "Plugin B must register HandoverStateService.",
        )
        assertTrue(
            xml.contains("\"workflow.handover\""),
            "Plugin B must register the workflow.handover notification group.",
        )
    }

    @Test
    fun `B build bundles the handover module`() {
        val build = text("plugin-b/build.gradle.kts")
        assertTrue(
            build.contains("project(\":handover\")"),
            "Plugin B's build must bundle the :handover module.",
        )
    }

    @Test
    fun `no A-resident src-main Kotlin file references the B-only workflow-handover notification group`() {
        val aModules = listOf("core", "jira", "bamboo", "sonar", "pullrequest", "automation", "agent", "web", "document")
        val hits = mutableListOf<String>()
        for (module in aModules) {
            val srcMain = File(repoRoot, "$module/src/main")
            if (!srcMain.exists()) continue
            srcMain.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { f -> if (f.readText().contains("\"workflow.handover\"")) hits.add("$module/${f.name}") }
        }
        assertTrue(
            hits.isEmpty(),
            "A-resident src/main files must not reference the B-only \"workflow.handover\" group: $hits",
        )
    }
}

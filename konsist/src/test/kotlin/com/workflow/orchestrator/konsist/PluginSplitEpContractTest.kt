package com.workflow.orchestrator.konsist

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the plugin-split extension-point wiring across the two-plugin boundary by inspecting the
 * actual plugin.xml registrations (with XML comments stripped first, mirroring
 * [SettingsAnchorContractTest]):
 *  - Plugin A declares the two split EPs (`workflowConfig`, `agentToolContributor`) and registers
 *    its own [DefaultWorkflowConfig] as the lowest-priority fallback.
 *  - Plugin B registers its lower-order [CompanyBWorkflowConfig] override and its
 *    [CompanyBToolContributor], and HARD-depends on A so B's classloader is a child of A's.
 * These are the relationships the runIde smoke verified manually — turning a runtime-only break
 * (B's EPs disabled, or A's EP removed) into a failing test. NOTE: reads plugin.xml as TEXT (not
 * Konsist.scopeFromProject, which scopes Kotlin sources only) — appropriate for resource XML.
 */
class PluginSplitEpContractTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }

    /** Reads a repo-relative file with XML comments stripped (mirrors SettingsAnchorContractTest). */
    private fun xmlText(relPath: String): String =
        File(repoRoot(), relPath).readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private val aXml by lazy { xmlText("src/main/resources/META-INF/plugin.xml") }
    private val bXml by lazy { xmlText("plugin-b/src/main/resources/META-INF/plugin.xml") }

    @Test
    fun `A declares the workflowConfig EP`() {
        assertTrue(
            aXml.contains("qualifiedName=\"com.workflow.orchestrator.workflowConfig\""),
            "Root plugin.xml must declare the workflowConfig extension point — it is the fork-facing " +
                "seam DefaultWorkflowConfig and CompanyBWorkflowConfig register against.",
        )
    }

    @Test
    fun `A declares the agentToolContributor EP`() {
        assertTrue(
            aXml.contains("qualifiedName=\"com.workflow.orchestrator.agentToolContributor\""),
            "Root plugin.xml must declare the agentToolContributor extension point — it is the seam " +
                "plugin B contributes agent tools through (CompanyBToolContributor).",
        )
    }

    @Test
    fun `A registers DefaultWorkflowConfig as the fallback impl`() {
        assertTrue(
            aXml.contains("com.workflow.orchestrator.core.config.DefaultWorkflowConfig"),
            "Root plugin.xml must register DefaultWorkflowConfig (the lowest-priority fallback so any " +
                "fork-registered WorkflowConfig wins).",
        )
    }

    @Test
    fun `A declares the configPreset EP`() {
        assertTrue(
            aXml.contains("qualifiedName=\"com.workflow.orchestrator.configPreset\""),
            "Root plugin.xml must declare the configPreset extension point — it is the application-level " +
                "seam for Plugin B to supply company default VALUES into A's neutral settings.",
        )
    }

    @Test
    fun `A registers DefaultConfigPreset as the no-op fallback impl`() {
        assertTrue(
            aXml.contains("com.workflow.orchestrator.core.config.DefaultConfigPreset"),
            "Root plugin.xml must register DefaultConfigPreset (A's no-op fallback so B's order=0 " +
                "implementation wins when registered).",
        )
    }

    @Test
    fun `B registers CompanyBWorkflowConfig as the override impl`() {
        assertTrue(
            bXml.contains("com.workflow.orchestrator.companyb.CompanyBWorkflowConfig"),
            "plugin-b plugin.xml must register CompanyBWorkflowConfig (the order=0 override that beats " +
                "A's DefaultWorkflowConfig).",
        )
    }

    @Test
    fun `B registers CompanyBToolContributor`() {
        assertTrue(
            bXml.contains("com.workflow.orchestrator.companyb.CompanyBToolContributor"),
            "plugin-b plugin.xml must register CompanyBToolContributor so its tools reach the agent " +
                "via the agentToolContributor EP.",
        )
    }

    @Test
    fun `B registers CompanyBConfigPreset as the company-values preset impl`() {
        assertTrue(
            bXml.contains("com.workflow.orchestrator.companyb.CompanyBConfigPreset"),
            "plugin-b plugin.xml must register CompanyBConfigPreset so the configPreset EP delivers " +
                "company default VALUES (bamboo var, clipboard chips, develop branch) to A's seeder.",
        )
    }

    @Test
    fun `B hard-depends on A`() {
        val hardDepends = Regex("<depends>([^<]*)</depends>", RegexOption.DOT_MATCHES_ALL)
            .findAll(bXml).map { it.groupValues[1].trim() }.toList()
        assertTrue(
            hardDepends.any { it == "com.workflow.orchestrator.plugin" },
            "plugin-b plugin.xml must HARD-depend on A (a non-optional <depends> on " +
                "com.workflow.orchestrator.plugin) so B's classloader is a child of A's. " +
                "Found <depends> entries: $hardDepends",
        )
    }
}

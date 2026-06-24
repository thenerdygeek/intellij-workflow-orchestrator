package com.workflow.orchestrator.konsist

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the settings-section contribution anchor across the two-plugin boundary by inspecting the
 * actual <projectConfigurable> ELEMENTS (not loose substrings) in both plugin.xml files:
 *  - Plugin A's root plugin.xml MUST register WorkflowSettingsConfigurable with the stable settings
 *    group id `workflow.orchestrator` (the plugin.xml `id` attribute — that, NOT `getId()`, is what
 *    `parentId` resolves against).
 *  - Plugin B MUST nest CompanyBSettingsConfigurable under that exact id via `parentId`.
 * Because B's verifyPlugin is disabled, a typo in B's `instance` FQN would otherwise surface only at
 * runIde — so this also pins the instance FQNs + B's class-file existence (turns a runtime-only break
 * into a failing test). NOTE: reads plugin.xml as TEXT (not Konsist.scopeFromProject, which scopes
 * Kotlin sources only) — appropriate for asserting on resource XML; comments are stripped first.
 */
class SettingsAnchorContractTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
    }

    /** All <projectConfigurable …> opening-tag texts, with XML comments stripped first. */
    private fun projectConfigurables(relPath: String): List<String> {
        val xml = File(repoRoot(), relPath).readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("<projectConfigurable\\b[^>]*?>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }.toList()
    }

    @Test
    fun `A registers the anchor id on WorkflowSettingsConfigurable`() {
        val anchor = projectConfigurables("src/main/resources/META-INF/plugin.xml").firstOrNull {
            it.contains("id=\"workflow.orchestrator\"") && it.contains("WorkflowSettingsConfigurable")
        }
        assertTrue(
            anchor != null,
            "Root plugin.xml must register WorkflowSettingsConfigurable with id=\"workflow.orchestrator\" " +
                "(the stable anchor depending plugins nest under).",
        )
    }

    @Test
    fun `B nests CompanyBSettingsConfigurable under A's anchor`() {
        val nested = projectConfigurables("plugin-b/src/main/resources/META-INF/plugin.xml").firstOrNull {
            it.contains("parentId=\"workflow.orchestrator\"") && it.contains("CompanyBSettingsConfigurable")
        }
        assertTrue(
            nested != null,
            "plugin-b plugin.xml must register <projectConfigurable parentId=\"workflow.orchestrator\" " +
                "instance=\"…CompanyBSettingsConfigurable\"> so B's page nests under A's group.",
        )
    }

    @Test
    fun `B's declared configurable class exists`() {
        val f = File(
            repoRoot(),
            "plugin-b/src/main/kotlin/com/workflow/orchestrator/companyb/CompanyBSettingsConfigurable.kt",
        )
        assertTrue(
            f.exists() && f.readText().contains("class CompanyBSettingsConfigurable"),
            "B's declared instance FQN must resolve to a real class (B's verifyPlugin is disabled, so a " +
                "typo'd instance would otherwise surface only at runIde).",
        )
    }
}

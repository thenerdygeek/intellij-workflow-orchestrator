package com.workflow.orchestrator.konsist

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Enforces the documented architecture rule: feature modules depend on :core only — never on
 * each other or on :agent/:automation/:web/:document. The rule is otherwise convention-only
 * (enforced by code review); this compiles it in so a careless edit or a downstream fork can't
 * silently violate it.
 *
 * We scan the whole project and bucket each main-source file to its Gradle module by path, then
 * assert its imports never reach into another module's package namespace.
 */
class ModuleBoundaryTest {

    /** Feature modules that must depend on :core only. */
    private val featureModules = listOf("jira", "bamboo", "sonar", "pullrequest", "handover")

    /** All non-core module package leaves under com.workflow.orchestrator.* */
    private val allModulePackages =
        listOf("jira", "bamboo", "sonar", "pullrequest", "handover", "agent", "automation", "web", "document")

    @Test
    fun `feature modules import core only, not other feature or app modules`() {
        val files = Konsist.scopeFromProject().files
            .filterNot { it.path.contains("/plugin-b/") }
        val violations = mutableListOf<String>()

        featureModules.forEach { module ->
            val forbiddenPrefixes =
                (allModulePackages - module).map { "com.workflow.orchestrator.$it." }
            val moduleMainFiles = files.filter { it.path.contains("/$module/src/main/") }

            moduleMainFiles.forEach { file ->
                file.imports.forEach { import ->
                    if (forbiddenPrefixes.any { import.name.startsWith(it) }) {
                        violations += "${file.path}: imports ${import.name}"
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Module boundary violations — feature modules may import :core only:\n" +
                violations.joinToString("\n"),
        )
    }
}

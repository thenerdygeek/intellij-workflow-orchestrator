package com.workflow.orchestrator.konsist

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginBDependencyDirectionTest {
    @Test fun `A modules do not import plugin-b`() {
        val violations = Konsist.scopeFromProject().files
            .filterNot { it.path.contains("/plugin-b/") }
            .filter { it.path.contains("/src/main/") }
            .flatMap { f -> f.imports.filter { it.name.startsWith("com.workflow.orchestrator.companyb.") }
                .map { "${f.path}: imports ${it.name}" } }
        assertTrue(violations.isEmpty(), "A must not depend on plugin B:\n" + violations.joinToString("\n"))
    }
}

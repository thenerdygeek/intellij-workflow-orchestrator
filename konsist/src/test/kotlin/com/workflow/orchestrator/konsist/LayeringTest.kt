package com.workflow.orchestrator.konsist

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The documented layering is api/ -> service/ -> ui/ -> listeners/. This test enforces the single
 * most important, most-misused direction: the api/ layer is the lowest layer, so it must not depend
 * upward on service/ or ui/. Other directions (service->ui, ui->listeners, api->listeners) are not
 * yet asserted here — a Phase 1 extension. Scoped to feature modules with the documented structure;
 * modules without an api/ package (pullrequest, handover) contribute no checks.
 */
class LayeringTest {

    private val layeredModules = listOf("jira", "bamboo", "sonar", "pullrequest", "handover")

    @Test
    fun `api layer does not depend on service or ui layers`() {
        val files = Konsist.scopeFromProject().files
        val violations = mutableListOf<String>()

        layeredModules.forEach { module ->
            val apiFiles = files.filter {
                it.path.contains("/$module/src/main/") && it.path.contains("/api/")
            }
            apiFiles.forEach { file ->
                file.imports.forEach { import ->
                    val reachesUp =
                        import.name.contains(".$module.ui.") ||
                            import.name.contains(".$module.service.")
                    if (reachesUp) {
                        violations += "${file.path}: api/ imports ${import.name}"
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Layering violations — api/ must not depend on service/ or ui/:\n" +
                violations.joinToString("\n"),
        )
    }
}

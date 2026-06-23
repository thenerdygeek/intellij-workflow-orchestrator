package com.workflow.orchestrator.konsist

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicApiSurfaceTest {
    private val bFacingEpInterfaces = listOf(
        "AgentToolContributor",
        "WorkflowConfig",
        "AuthProvider",
        "FeatureRegistry",
        "JiraTicketProvider",
        "ToolProtocol",
        "NativeProtocol",
        "LlmProvider",
        "CiService",
    )

    @Test fun `B-facing EP interfaces are public`() {
        val targets = Konsist.scopeFromProject().files
            .flatMap { it.interfaces() }
            .filter { it.name in bFacingEpInterfaces }

        // Self-guard: if the scope/API is wrong, fail loudly instead of silently passing on empty.
        assertTrue(targets.isNotEmpty(), "Konsist found none of the target EP interfaces — check API/scope")

        val violations = targets.filter { it.hasInternalModifier }.map { "${it.name} is `internal`" }
        assertTrue(
            violations.isEmpty(),
            "EP interfaces must be public for plugin B:\n" + violations.joinToString("\n"),
        )
    }
}

package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentControllerDisplayWindowWiringTest {
    private val ctrl = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt",
    ).readText()
    private val registry = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentControllerRegistry.kt",
    ).readText()

    @Test
    fun `registry exposes an explicit getController()`() {
        assertTrue(registry.contains("fun getController()"), "AgentControllerRegistry must expose fun getController()")
    }

    @Test
    fun `selector and bar route DISPLAY through the resolver keyed on the selected model`() {
        assertTrue(ctrl.contains("fun selectedModelId()"), "must define selectedModelId()")
        assertTrue(
            ctrl.contains("getEffectiveContextWindow().maxInputTokens(selectedModelId())"),
            "the usage-bar provider must key the resolver on the selected model",
        )
        assertTrue(
            Regex("getEffectiveContextWindow\\(\\)\\.maxInputTokens\\(m\\.id\\)").containsMatchIn(ctrl),
            "the model-picker row must read the override-aware window via the resolver",
        )
    }

    @Test
    fun `exposes the settings-apply notification entry point`() {
        assertTrue(
            ctrl.contains("fun notifyContextWindowOverridesChanged()"),
            "must expose notifyContextWindowOverridesChanged()",
        )
    }
}

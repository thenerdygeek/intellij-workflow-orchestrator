package com.workflow.orchestrator.core.capability

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultFeatureRegistryTest {
    // The default registry never dereferences the project (it only keys settings lookup), so an
    // un-stubbed mock is sufficient; the injected stateProvider ignores it and returns our fixture.
    private val project = mockk<Project>()
    private val state = PluginSettings.State().apply {
        enableWebFetch = false
        enableWebSearch = true
        enableResearchSubagent = false
        enableImageInput = true
        enableAiTitleGeneration = false
    }
    private val registry = DefaultFeatureRegistry { state }

    @Test
    fun `maps each feature to its backing setting`() {
        assertFalse(registry.isEnabled(project, PluginFeature.WEB_FETCH))
        assertTrue(registry.isEnabled(project, PluginFeature.WEB_SEARCH))
        assertFalse(registry.isEnabled(project, PluginFeature.RESEARCH_SUBAGENT))
        assertTrue(registry.isEnabled(project, PluginFeature.IMAGE_INPUT))
        assertFalse(registry.isEnabled(project, PluginFeature.AI_TITLE_GENERATION))
    }

    @Test
    fun `default registry is lowest priority so forks win`() {
        assertEquals(Int.MAX_VALUE, registry.order)
    }
}

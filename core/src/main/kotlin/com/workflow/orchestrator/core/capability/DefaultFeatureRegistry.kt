package com.workflow.orchestrator.core.capability

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings

/**
 * Base feature resolution: each [PluginFeature] maps to its project-level [PluginSettings] toggle.
 * Sits at the lowest priority ([order] = [Int.MAX_VALUE]) so any fork-registered [FeatureRegistry]
 * wins. The state source is injectable for tests; the `@JvmOverloads` no-arg constructor is what the
 * platform instantiates for the EP (see the `@Service`/EP constructor trap in project memory).
 */
class DefaultFeatureRegistry @JvmOverloads constructor(
    private val stateProvider: (Project) -> PluginSettings.State = { PluginSettings.getInstance(it).state },
) : FeatureRegistry {
    override val order: Int get() = Int.MAX_VALUE

    override fun isEnabled(project: Project, feature: PluginFeature): Boolean {
        val state = stateProvider(project)
        return when (feature) {
            PluginFeature.WEB_FETCH -> state.enableWebFetch
            PluginFeature.WEB_SEARCH -> state.enableWebSearch
            PluginFeature.RESEARCH_SUBAGENT -> state.enableResearchSubagent
            PluginFeature.IMAGE_INPUT -> state.enableImageInput
            PluginFeature.AI_TITLE_GENERATION -> state.enableAiTitleGeneration
        }
    }
}

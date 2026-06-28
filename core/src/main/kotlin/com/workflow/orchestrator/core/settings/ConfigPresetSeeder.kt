package com.workflow.orchestrator.core.settings

import com.workflow.orchestrator.core.config.ConfigPreset
// NOTE: NEUTRAL_DEFAULT_TARGET_BRANCH is a top-level const in THIS package
// (com.workflow.orchestrator.core.settings) — reference it bare, NO import (a same-package
// import trips ktlint no-unused-imports / the detekt gate).

/**
 * Applies a [ConfigPreset]'s company default VALUES into [PluginSettings.State]. ONE-SHOT per
 * install ([PluginSettings.State.configPresetApplied]) AND per-field guarded ("field == A's neutral
 * default") so user edits / upgrader values are never clobbered and curated-away values are not
 * resurrected. The sentinel is set ONLY when a value-providing preset is present, so an A-alone
 * install (DefaultConfigPreset → all null) never stamps it and a later B install still seeds.
 */
object ConfigPresetSeeder {
    /** @return true if [state] was mutated. */
    fun seed(state: PluginSettings.State, preset: ConfigPreset): Boolean {
        if (state.configPresetApplied) return false
        val provides = preset.bambooBuildVariableName() != null ||
            preset.quickClipboardChips() != null ||
            preset.defaultTargetBranch() != null ||
            preset.copyrightTemplate() != null
        if (!provides) return false

        if (state.bambooBuildVariableName.isNullOrBlank()) {
            preset.bambooBuildVariableName()?.let { state.bambooBuildVariableName = it }
        }
        if (state.quickClipboardChips == PluginSettings.NEUTRAL_QUICK_CLIPBOARD_CHIPS) {
            preset.quickClipboardChips()?.let { state.quickClipboardChips = it.toMutableList() }
        }
        if (state.defaultTargetBranch == NEUTRAL_DEFAULT_TARGET_BRANCH) {
            preset.defaultTargetBranch()?.let { state.defaultTargetBranch = it }
        }
        if (state.copyrightTemplate.isNullOrBlank()) {
            preset.copyrightTemplate()?.let { state.copyrightTemplate = it }
        }
        state.configPresetApplied = true
        return true
    }
}

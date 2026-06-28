package com.workflow.orchestrator.core.config

import com.intellij.openapi.extensions.ExtensionPointName
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Application-level EP for a depending plugin (Plugin B) to supply company default VALUES into A's
 * neutral settings. A owns the seeding LOGIC (ConfigPresetSeeder: one-shot per install + per-field
 * "== neutral default" guard); B owns the values. Each method returns null = "no opinion" (A keeps
 * its neutral default). Lowest [order] wins (mirrors WorkflowConfig). public + @InternalApi
 * (unfrozen — B recompiles in lockstep; NOT internal, NOT @StableApi). No Project param — values are
 * company-global; the seeder applies them per-project.
 */
@InternalApi
interface ConfigPreset {
    val order: Int get() = 0
    fun bambooBuildVariableName(): String? = null
    fun quickClipboardChips(): List<String>? = null
    fun defaultTargetBranch(): String? = null
    fun copyrightTemplate(): String? = null

    companion object {
        val EP_NAME: ExtensionPointName<ConfigPreset> =
            ExtensionPointName.create("com.workflow.orchestrator.configPreset")

        fun resolve(): ConfigPreset =
            runCatching { lowestOrderOf(EP_NAME.extensionList) }.getOrNull() ?: DefaultConfigPreset()

        internal fun lowestOrderOf(providers: List<ConfigPreset>): ConfigPreset? =
            providers.minByOrNull { it.order }
    }
}

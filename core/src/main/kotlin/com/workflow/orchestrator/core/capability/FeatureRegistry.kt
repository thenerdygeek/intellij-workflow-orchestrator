package com.workflow.orchestrator.core.capability

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.api.StableApi

/**
 * STABLE, fork-facing extension point for feature enablement. The base ships
 * [DefaultFeatureRegistry] (reads project-level settings). A company fork registers its own
 * implementation (license server, LDAP group membership, central admin policy) via the
 * `featureRegistry` EP **without editing `:core`** — the lowest-[order] provider wins.
 *
 * See docs/STABLE-API.md and FORKING.md.
 */
@StableApi(since = "0.86")
interface FeatureRegistry {
    /** Lower runs first; a fork overrides the base (which sits at the lowest priority). */
    val order: Int get() = 0

    /** Whether [feature] is enabled for [project]. */
    fun isEnabled(project: Project, feature: PluginFeature): Boolean

    companion object {
        val EP_NAME: ExtensionPointName<FeatureRegistry> =
            ExtensionPointName.create("com.workflow.orchestrator.featureRegistry")

        /**
         * The active registry: the lowest-[order] registered provider, else a fresh
         * [DefaultFeatureRegistry]. [runCatching]-guarded so it degrades to the default in
         * environments without the extension-point system (e.g. plain unit tests).
         */
        fun resolve(): FeatureRegistry =
            runCatching { EP_NAME.extensionList.minByOrNull { it.order } }.getOrNull()
                ?: DefaultFeatureRegistry()
    }
}

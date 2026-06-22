package com.workflow.orchestrator.core.config

import com.intellij.openapi.extensions.ExtensionPointName
import com.workflow.orchestrator.core.api.StableApi
import com.workflow.orchestrator.core.model.ServiceType

/**
 * STABLE, fork-facing extension point for company-variable configuration. The base ships
 * [DefaultWorkflowConfig] (reads the IDE's connection settings). A company fork registers its own
 * implementation (env vars, a mounted config file, LDAP / remote config) via the `workflowConfig`
 * EP **without editing `:core`** — the lowest-[order] provider wins.
 *
 * See docs/STABLE-API.md and FORKING.md.
 */
@StableApi(since = "0.86")
interface WorkflowConfig {
    /** Lower runs first; a fork overrides the base (which sits at the lowest priority). */
    val order: Int get() = 0

    /** Configured base URL for [service], or `""` if unset / not applicable. */
    fun baseUrl(service: ServiceType): String

    companion object {
        val EP_NAME: ExtensionPointName<WorkflowConfig> =
            ExtensionPointName.create("com.workflow.orchestrator.workflowConfig")

        /**
         * The active config: the lowest-[order] registered provider, else a fresh
         * [DefaultWorkflowConfig]. [runCatching]-guarded so it degrades to the default in
         * environments without the extension-point system (e.g. plain unit tests).
         */
        fun resolve(): WorkflowConfig =
            runCatching { lowestOrderOf(EP_NAME.extensionList) }.getOrNull()
                ?: DefaultWorkflowConfig()

        /** Pure selection: the lowest-[order] provider, or null if none. Split out from the platform
         *  extension-list fetch in [resolve] so the ordering rule is unit-testable without a platform fixture. */
        internal fun lowestOrderOf(providers: List<WorkflowConfig>): WorkflowConfig? =
            providers.minByOrNull { it.order }
    }
}

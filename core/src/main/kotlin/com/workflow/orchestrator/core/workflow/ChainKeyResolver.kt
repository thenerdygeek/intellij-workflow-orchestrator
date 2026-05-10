package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Extension point for cross-module branch-chain-key resolution. Implemented by
 * `:bamboo`; consumed by `:core`'s `WorkflowContextService.focusPr` cascade to
 * convert a `(parentPlanKey, branchName)` pair into the addressable branch
 * chain key (e.g. `PROJ-PLANKEY523`) that downstream callers (Build / Automation
 * / Quality / agent) read off `BuildRef.chainKey`.
 *
 * Mirrors the [LatestBuildLookup] EP pattern — `:core` defines the interface,
 * `:bamboo` provides the implementation. This avoids `:core → :bamboo` while
 * still letting the cascade ask Bamboo for the correct branch-chain mapping
 * (Bamboo's branch listing's `shortName` is what matches the git branch name;
 * the legacy `name`-based comparison silently mismatched and substituted the
 * master plan, producing wrong-branch builds).
 */
interface ChainKeyResolver {
    /**
     * Resolve the branch-chain key for a `(parentPlanKey, branchName)` pair, or
     * null if no chain exists for that branch under [parentPlanKey].
     *
     * **No fallback to master.** When no branch chain exists, the caller must
     * treat the build context as "no build for this branch" rather than
     * substituting the master chain — per the project directive that faulty
     * fallbacks are worse than no data.
     *
     * Suspend; off-EDT.
     */
    suspend fun resolveChainKey(
        project: Project,
        parentPlanKey: String,
        branchName: String,
    ): String?

    companion object {
        val EP_NAME = ExtensionPointName.create<ChainKeyResolver>(
            "com.workflow.orchestrator.chainKeyResolver"
        )
        fun getInstance(): ChainKeyResolver? = EP_NAME.extensionList.firstOrNull()
    }
}

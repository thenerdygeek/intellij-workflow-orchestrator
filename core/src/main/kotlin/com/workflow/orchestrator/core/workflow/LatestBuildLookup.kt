package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.BuildRef

/**
 * Extension point for cross-module latest-build lookup by **chain key**. Implemented
 * by `:bamboo`; consumed by `:core`'s `WorkflowContextService.focusPr` cascade to
 * derive [BuildRef].
 *
 * `chainKey` is the resolved branch-chain key (e.g. `PROJ-PLANKEY523`) — the
 * single addressable identifier of the branch's build chain. Resolution from a
 * `(parentPlanKey, branch)` pair is the responsibility of [ChainKeyResolver];
 * once resolved, the chain key fully replaces the legacy `(planKey, branch)`
 * shape that produced wrong-branch results when Bamboo's branch label disagreed
 * with the git branch name.
 */
interface LatestBuildLookup {
    /**
     * Returns the latest build for [chainKey] (the unbranched
     * `/result/{chainKey}/latest` endpoint), or null on miss/error.
     * Suspend; off-EDT.
     */
    suspend fun fetchLatestBuild(project: Project, chainKey: String): BuildRef?

    companion object {
        val EP_NAME = ExtensionPointName.create<LatestBuildLookup>(
            "com.workflow.orchestrator.latestBuildLookup"
        )
        fun getInstance(): LatestBuildLookup? = EP_NAME.extensionList.firstOrNull()
    }
}

package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.BuildRef

/**
 * Extension point for cross-module latest-build lookup by plan + branch. Implemented by
 * :bamboo; consumed by :core's [WorkflowContextService.focusPr] cascade to derive [BuildRef].
 */
interface LatestBuildLookup {
    /** Returns the latest build for [planKey] on [branch], or null on miss/error. Suspend; off-EDT. */
    suspend fun fetchLatestBuild(project: Project, planKey: String, branch: String): BuildRef?

    companion object {
        val EP_NAME = ExtensionPointName.create<LatestBuildLookup>(
            "com.workflow.orchestrator.latestBuildLookup"
        )
        fun getInstance(): LatestBuildLookup? = EP_NAME.extensionList.firstOrNull()
    }
}

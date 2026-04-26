package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.workflow.LatestBuildLookup

/**
 * Phase 5 Task 4 — `:bamboo` implementation of the [LatestBuildLookup] EP.
 *
 * Bridges [com.workflow.orchestrator.bamboo.api.BambooApiClient.getLatestResult] into the
 * cross-module [BuildRef] DTO that `:core`'s `WorkflowContextService.focusPr` cascade consumes
 * to derive `focusBuild`. Returns null on any error (auth, network, parse, missing service)
 * so the cascade can degrade gracefully without surfacing a transient build lookup failure
 * as a focus-cascade error.
 *
 * Same EP pattern as [com.workflow.orchestrator.pullrequest.workflow.OpenPrListerImpl].
 */
class LatestBuildLookupImpl : LatestBuildLookup {
    private val log = Logger.getInstance(LatestBuildLookupImpl::class.java)

    override suspend fun fetchLatestBuild(project: Project, planKey: String, branch: String): BuildRef? {
        val bambooService = project.getService(BambooServiceImpl::class.java) ?: return null
        val client = bambooService.client ?: return null
        return when (val result = client.getLatestResult(planKey, branch)) {
            is ApiResult.Success -> {
                val dto = result.data
                BuildRef(
                    planKey = planKey,
                    buildNumber = dto.buildNumber,
                    branch = branch,
                    selectedJobKey = null,
                )
            }
            is ApiResult.Error -> {
                log.warn("[Bamboo:LatestBuild] $planKey@$branch failed: ${result.message}")
                null
            }
        }
    }
}

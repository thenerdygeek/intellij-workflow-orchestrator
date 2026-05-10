package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.workflow.LatestBuildLookup

/**
 * Phase A — `:bamboo` implementation of the [LatestBuildLookup] EP.
 *
 * Bridges [com.workflow.orchestrator.bamboo.api.BambooApiClient.getLatestResult] (the
 * unbranched `/result/{chainKey}/latest` form) into the cross-module [BuildRef] DTO
 * that `:core`'s `WorkflowContextService.focusPr` cascade consumes to derive
 * `focusBuild`. Returns null on any error (auth, network, parse, missing service)
 * so the cascade can degrade gracefully without surfacing a transient build lookup
 * failure as a focus-cascade error.
 *
 * **chain-key only.** The legacy `(planKey, branch)` shape that built
 * `/result/{planKey}/branch/{name}/latest` URLs is gone — Phase A consolidates on a
 * single addressable identifier (`chainKey`) per [ChainKeyResolver] resolution.
 *
 * Same EP pattern as [com.workflow.orchestrator.pullrequest.workflow.OpenPrListerImpl].
 */
class LatestBuildLookupImpl : LatestBuildLookup {
    private val log = Logger.getInstance(LatestBuildLookupImpl::class.java)

    override suspend fun fetchLatestBuild(project: Project, chainKey: String): BuildRef? {
        if (chainKey.isBlank()) return null
        val bambooService = project.getService(BambooServiceImpl::class.java) ?: return null
        val client = bambooService.client ?: return null
        // Unbranched form: branch=null forces `/result/{chainKey}/latest`.
        return when (val result = client.getLatestResult(chainKey)) {
            is ApiResult.Success -> {
                val dto = result.data
                BuildRef(
                    planKey = chainKey,
                    buildNumber = dto.buildNumber,
                    branch = "",
                    selectedJobKey = null,
                    chainKey = chainKey,
                )
            }
            is ApiResult.Error -> {
                log.warn("[Bamboo:LatestBuild] $chainKey failed: ${result.message}")
                null
            }
        }
    }
}

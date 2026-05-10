package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.bamboo.service.PlanDetectionService
import com.workflow.orchestrator.core.workflow.ChainKeyResolver

/**
 * Phase A — `:bamboo` implementation of the [ChainKeyResolver] EP.
 *
 * Delegates to [PlanDetectionService.resolveBranchKeyOrNull] so the matching rule
 * (`apiClient.getPlanBranches(parent)` matched by `shortName`, the field that actually
 * carries the git branch name) stays in a single place. Returns null when:
 *
 *  - [BambooServiceImpl] is unavailable (project not initialised yet)
 *  - the lazy [BambooServiceImpl.client] is null (Bamboo not configured)
 *  - [parentPlanKey] looks like a branch chain key already (`PROJ-PLAN-7` shape)
 *  - no branch chain exists for [branchName] under [parentPlanKey]
 *
 * **No master fallback.** Per project directive ("better to see no data than incorrect
 * data"): a missing branch chain is reported as null and the cascade leaves
 * `BuildRef.chainKey` null + `focusBuild = null`, instead of silently substituting the
 * master chain's latest build (the bug this Phase A unblocks).
 */
class ChainKeyResolverImpl : ChainKeyResolver {
    private val log = Logger.getInstance(ChainKeyResolverImpl::class.java)

    override suspend fun resolveChainKey(
        project: Project,
        parentPlanKey: String,
        branchName: String,
    ): String? {
        if (parentPlanKey.isBlank() || branchName.isBlank()) return null
        val bambooService = project.getService(BambooServiceImpl::class.java) ?: return null
        val client = bambooService.client ?: return null
        // `PluginSettings` is unused on the `resolveBranchKeyOrNull` path (only the auto-detect
        // T4 deep-scan gate + validation cache reads it), so we don't fetch it here. Avoids
        // pulling a project-service dependency through tests that don't need one.
        val planDetection = PlanDetectionService(client, null)
        return try {
            planDetection.resolveBranchKeyOrNull(parentPlanKey, branchName)
        } catch (t: Throwable) {
            // Defensive: any unexpected failure (parse error, transport hiccup) collapses
            // to null so the cascade doesn't surface a transient failure as "no build" vs
            // "wrong build" — both surface as the empty state, never wrong data.
            log.warn("[Bamboo:Plan] resolveChainKey($parentPlanKey, '$branchName') failed: ${t.message}")
            null
        }
    }
}

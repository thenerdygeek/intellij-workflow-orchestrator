package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.model.BambooPlanRef
import com.workflow.orchestrator.core.model.ApiResult

/**
 * Single authority for turning a (masterKey, branchName) pair into a typed
 * [BambooPlanRef], sourced entirely from the Bamboo API — never from key string shape.
 *
 * Bamboo's real three-state model (probe §3.1, DC 10.2.14):
 *  1. a child branch plan exists  → [BambooPlanRef.BranchPlan] (its own /plan/{master}/branch key)
 *  2. branch == master's tracked (default) branch → [BambooPlanRef.MasterTrackedBranch]
 *  3. neither → null (strict empty state; no master substitution)
 *
 * State 2 is detected by comparing branch NAMES against the repo's default branch
 * (real git data via DefaultBranchResolver, supplied by the caller) — neither
 * `/plan/{key}` nor `/result/{key}/latest` exposes a tracked-branch label to read instead.
 */
class BambooPlanResolver(private val api: BambooApiClient) {
    private val log = Logger.getInstance(BambooPlanResolver::class.java)

    suspend fun resolve(
        masterKey: String,
        branchName: String?,
        repoDefaultBranch: String? = null,
    ): BambooPlanRef? {
        if (masterKey.isBlank()) return null
        if (branchName.isNullOrBlank()) return BambooPlanRef.Master(masterKey)

        val branches = when (val r = api.getPlanBranches(masterKey)) {
            is ApiResult.Success -> r.data
            is ApiResult.Error -> {
                log.warn("[Bamboo:Plan] getPlanBranches($masterKey) failed: ${r.message} — resolving null")
                return null
            }
        }

        val match = branches.firstOrNull { it.shortName?.equals(branchName, ignoreCase = false) == true }
        if (match != null) {
            log.info("[Bamboo:Plan] '$branchName' → BranchPlan ${match.key} under $masterKey")
            return BambooPlanRef.BranchPlan(match.key, masterKey, branchName)
        }

        if (repoDefaultBranch != null && branchName == repoDefaultBranch) {
            log.info("[Bamboo:Plan] '$branchName' is repo default branch → MasterTrackedBranch $masterKey")
            return BambooPlanRef.MasterTrackedBranch(masterKey, branchName)
        }

        log.info("[Bamboo:Plan] No branch plan for '$branchName' under $masterKey (not default branch) → null")
        return null
    }
}

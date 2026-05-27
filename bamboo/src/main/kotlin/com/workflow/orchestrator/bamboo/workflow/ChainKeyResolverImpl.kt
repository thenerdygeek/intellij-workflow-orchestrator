package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.model.BambooPlanRef
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.bamboo.service.PlanDetectionService
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import com.workflow.orchestrator.core.workflow.ChainKeyResolver

/**
 * `:bamboo` implementation of the [ChainKeyResolver] EP.
 *
 * Calls [PlanDetectionService.resolvePlanRef] with the repo's default branch so that the
 * master-tracked-branch case (state 2: the current branch IS the master plan's tracked
 * branch, e.g. `develop`) resolves to the master plan key instead of falling through to the
 * strict empty state (state 3). Returns null when:
 *
 *  - [BambooServiceImpl] is unavailable (project not initialised yet)
 *  - the lazy [BambooServiceImpl.client] is null (Bamboo not configured)
 *  - no branch plan exists for [branchName] under [parentPlanKey] and the branch is not the
 *    repo's default branch (state 3 — strict empty state, no master substitution)
 *  - the resolved ref is [BambooPlanRef.Master] (would occur only if [branchName] is blank,
 *    but that is guarded above — included for exhaustiveness)
 *
 * **No master fallback for a non-matching feature branch.** Per project directive ("better
 * to see no data than incorrect data"): state 3 surfaces as null so the cascade leaves
 * `BuildRef.chainKey` null + `focusBuild = null` rather than substituting the master
 * chain's latest build.
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
        val planDetection = PlanDetectionService(client, null)
        return try {
            // Supply the repo's default branch so the master-tracked-branch case (state 2)
            // resolves to the master plan key instead of the strict empty state (state 3).
            val defaultBranch = resolveRepoDefaultBranch(project)
            planDetection.resolvePlanRef(parentPlanKey, branchName, defaultBranch)?.let {
                // No master substitution for a non-matching feature branch (state 3 → null).
                if (it is BambooPlanRef.Master) null else it.planKey
            }
        } catch (t: Throwable) {
            // Defensive: any unexpected failure (parse error, transport hiccup) collapses
            // to null so the cascade doesn't surface a transient failure as "no build" vs
            // "wrong build" — both surface as the empty state, never wrong data.
            log.warn("[Bamboo:Plan] resolveChainKey($parentPlanKey, '$branchName') failed: ${t.message}")
            null
        }
    }

    /** Repo default branch (real git data) for the master-tracked-branch detection; null if unavailable. */
    private suspend fun resolveRepoDefaultBranch(project: Project): String? = try {
        val repo = RepoContextResolver.getInstance(project).resolvePrimaryGitRepo()
        repo?.let { DefaultBranchResolver.getInstance(project).resolve(it) }
    } catch (t: Throwable) {
        log.warn("[Bamboo:Plan] default-branch resolution failed: ${t.message}")
        null
    }
}

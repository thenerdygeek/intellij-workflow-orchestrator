package com.workflow.orchestrator.agent.tools.delegation

import com.workflow.orchestrator.agent.prompt.SystemPrompt

/**
 * Pure composition of the cross-IDE delegation targets surfaced in the system prompt
 * (Phase 3 cut G, incision 1 — extracted from `AgentService.computeDelegationTargetsForPrompt`).
 *
 * The caller owns the side effects (settings gate + the two socket probes that yield
 * [recents] and [discovered]); this object owns only the dedup-merge-filter-map decision so
 * it is unit-testable without the platform:
 *  - [discovered] entries whose `projectPath` is already in [recents] are dropped (recents win),
 *  - entries with status `"missing"` (path no longer on disk) are noise to the LLM and removed,
 *  - the survivors map to [SystemPrompt.DelegationTarget], recents first then deduped discovered.
 */
object DelegationTargetComposer {

    private const val MISSING_STATUS = "missing"

    fun compose(
        recents: List<DelegationTool.RecentEntry>,
        discovered: List<DelegationTool.RecentEntry>,
    ): List<SystemPrompt.DelegationTarget> {
        val recentPaths = recents.map { it.projectPath }.toSet()
        val dedupedDiscovered = discovered.filter { it.projectPath !in recentPaths }
        return (recents + dedupedDiscovered)
            .filter { it.status != MISSING_STATUS }
            .map { SystemPrompt.DelegationTarget(repoName = it.repoName, status = it.status) }
    }
}

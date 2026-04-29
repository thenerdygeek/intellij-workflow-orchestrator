package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.settings.RepoConfig

/**
 * One (repo, ticket-branch) pair returned by [TicketBranchLocator].
 *
 * `branchDisplayId` is the Bitbucket Server `displayId` field (e.g. `feature/ABC-123`)
 * — already a human-readable short ref.
 *
 * `targetBranchDisplayId` is the resolved default-merge-into branch for this repo
 * (e.g. `develop`, `main`). Comes from `DefaultBranchResolver` — null when the
 * resolver couldn't pin one (no Bitbucket connection, no local repo mounted).
 *
 * `isCheckedOut` is a snapshot computed at lookup time against the local
 * `GitRepository.currentBranchName`. The UI re-queries on
 * [com.workflow.orchestrator.core.events.WorkflowEvent.BranchChanged].
 *
 * `isPathMounted` is `true` iff [RepoConfig.localVcsRootPath] resolves to a
 * registered `GitRepository`. `false` means the row is for a configured repo
 * the IDE can no longer act on locally — UI should disable the inline Switch
 * affordance and show a tooltip explaining why.
 *
 * `additionalMatchCount` is the count of *other* branches in the same repo whose
 * displayId also matches the anchored ticket regex. The locator picks the
 * most-recently-modified (Bitbucket returns `orderBy=MODIFICATION`) and surfaces
 * the count so the UI can show a tooltip like `"+2 more matching branches in RepoA"`.
 * Zero in the common case.
 */
data class TicketRepoBranch(
    val repo: RepoConfig,
    val branchDisplayId: String,
    val targetBranchDisplayId: String?,
    val isCheckedOut: Boolean,
    val isPathMounted: Boolean,
    val additionalMatchCount: Int,
)

/**
 * Disambiguates the two distinct empty states the chip can render.
 *
 * - [Configured] is "we asked Bitbucket; here's what came back" — `rows` may still
 *   be empty (no repo had a matching branch).
 * - [NoReposConfigured] is "the user has not set up multi-repo settings yet, so
 *   we have no Bitbucket coords to query." UI should send the user to settings
 *   instead of saying "no branches found".
 */
sealed class LocateResult {
    data class Configured(val rows: List<TicketRepoBranch>) : LocateResult()
    object NoReposConfigured : LocateResult()
}

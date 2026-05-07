package com.workflow.orchestrator.bamboo.ui

import com.workflow.orchestrator.bamboo.model.BuildStatus
import com.workflow.orchestrator.bamboo.model.StageState

/**
 * Pure decision helper for the Build dashboard's "is this manual stage runnable
 * right now?" gate (PR 7 audit P1 #2).
 *
 * Bamboo's stage model is strictly ordered: stage N can only execute after
 * stage N-1 has finished Successfully. The plugin previously enabled the Run
 * link on every manual stage regardless of upstream state, then surfaced a 400
 * from Bamboo to the user. This helper centralizes the rule so the UI greys
 * out unrunnable stages with an actionable tooltip.
 *
 * Headers (`name` prefixed with `§`) are skipped — they're synthetic UI labels,
 * not real stages.
 *
 * The policy is deliberately conservative: when a prior stage has any state
 * other than [BuildStatus.SUCCESS], the manual stage is not runnable. This
 * matches Bamboo's server-side rule and avoids cases where the trigger would
 * 400 because an in-progress upstream stage has the build lock.
 */
object StageRunnabilityPolicy {

    /**
     * Header marker prefix used by [StageListPanel] for synthetic group rows.
     * Centralized here so the policy + the panel agree on what's a header.
     */
    const val HEADER_PREFIX: String = "§"

    /** True if [stage] is a synthetic header row (not a real stage). */
    fun isHeader(stage: StageState): Boolean = stage.name.startsWith(HEADER_PREFIX)

    /**
     * Returns true iff [stage] is the next stage that can be triggered manually.
     *
     * Conditions (all must hold):
     *  - Not a header row.
     *  - The stage is `manual = true`.
     *  - The stage's own status is not [BuildStatus.IN_PROGRESS] (already running).
     *  - Every job whose `stageName` sorts strictly before this stage's
     *    `stageName` (in the order they appear in [stages]) has status
     *    [BuildStatus.SUCCESS]. A single FAILED / IN_PROGRESS / PENDING /
     *    UNKNOWN upstream job blocks the stage.
     *
     * The order is taken from the input list — `:bamboo`'s `BambooServiceImpl`
     * already returns stages in plan order (it preserves the `?expand=stages`
     * response order), so we don't re-sort.
     */
    fun isNextRunnable(stages: List<StageState>, stage: StageState): Boolean {
        if (isHeader(stage)) return false
        if (!stage.manual) return false
        if (stage.status == BuildStatus.IN_PROGRESS) return false

        // Find the position of this stage's group in the plan-order sequence.
        val groupOrder = stages
            .filter { !isHeader(it) }
            .map { it.stageName }
            .distinct()
        val targetIndex = groupOrder.indexOf(stage.stageName)
        if (targetIndex < 0) return false  // stage is not in the list — defensive

        val priorGroups = groupOrder.take(targetIndex).toSet()
        val priorJobs = stages.filter { !isHeader(it) && it.stageName in priorGroups }
        return priorJobs.all { it.status == BuildStatus.SUCCESS }
    }

    /**
     * Returns the human-readable name of the first prior stage group whose
     * jobs are not all Successful — used by the UI tooltip to point the user
     * at exactly which stage they need to run/finish first.
     *
     * Returns null when [stage] is itself runnable (no blocker), is a header,
     * or is already in-progress.
     */
    fun firstBlockingStage(stages: List<StageState>, stage: StageState): String? {
        if (isHeader(stage) || !stage.manual || stage.status == BuildStatus.IN_PROGRESS) return null
        val groupOrder = stages
            .filter { !isHeader(it) }
            .map { it.stageName }
            .distinct()
        val targetIndex = groupOrder.indexOf(stage.stageName)
        if (targetIndex <= 0) return null

        for (i in 0 until targetIndex) {
            val groupName = groupOrder[i]
            val jobs = stages.filter { !isHeader(it) && it.stageName == groupName }
            if (jobs.any { it.status != BuildStatus.SUCCESS }) return groupName
        }
        return null
    }
}

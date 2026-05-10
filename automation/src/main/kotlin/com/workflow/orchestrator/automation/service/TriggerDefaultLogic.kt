package com.workflow.orchestrator.automation.service

import com.workflow.orchestrator.core.services.BambooService

/**
 * Sealed result of [resolveTriggerDefaultAction] — tells the caller what to do
 * after the plan-stages fetch + stale-filter.
 *
 * Keeping the decision logic here (out of [com.workflow.orchestrator.automation.ui.AutomationPanel])
 * makes it testable without IntelliJ platform infrastructure.
 */
sealed class TriggerDefaultAction {
    /** Use the given stages to enqueue immediately. */
    data class EnqueueWith(val stages: Set<String>) : TriggerDefaultAction()

    /**
     * No saved default (or saved default was entirely stale) — open the
     * customize dialog so the user can make an explicit choice.
     */
    object OpenCustomizeDialog : TriggerDefaultAction()

    /**
     * Plan-stage fetch failed. Surface [errorMessage] to the user and do NOT
     * trigger anything. Never falls back to "all stages" or "first stage".
     */
    data class FetchError(val errorMessage: String) : TriggerDefaultAction()
}

/**
 * Pure async logic for the "Trigger" split-button's primary click.
 *
 * 1. Calls [bambooService.getLatestBuild] to get the current plan's stage names
 *    so the stale-stage filter has real data.
 * 2. Passes them to [automationSettings.getSuiteDefaultStages] for stale filtering.
 * 3. Returns a [TriggerDefaultAction] that the UI layer can act on.
 *
 * **No fallback to "all stages" or "first stage"** — if the stage fetch fails,
 * [TriggerDefaultAction.FetchError] is returned and the caller must surface the
 * error without triggering anything. This implements the
 * "faulty fallbacks worse than no data" rule (Phase H / Blocker 2 fix).
 *
 * @param suitePlanKey the plan key for the selected suite.
 * @param bambooService service used to look up the latest build's stage list.
 * @param automationSettings service used to look up the saved default stages.
 */
suspend fun resolveTriggerDefaultAction(
    suitePlanKey: String,
    bambooService: BambooService,
    automationSettings: AutomationSettingsService
): TriggerDefaultAction {
    val latestBuildResult = bambooService.getLatestBuild(suitePlanKey)
    if (latestBuildResult.isError) {
        return TriggerDefaultAction.FetchError(latestBuildResult.summary)
    }

    val currentPlanStages: Set<String> = latestBuildResult.data
        ?.stages
        ?.map { it.name }
        ?.toSet()
        .orEmpty()

    val saved = automationSettings.getSuiteDefaultStages(
        suitePlanKey,
        currentPlanStages = currentPlanStages
    )

    return if (saved != null && saved.isNotEmpty()) {
        TriggerDefaultAction.EnqueueWith(saved)
    } else {
        TriggerDefaultAction.OpenCustomizeDialog
    }
}

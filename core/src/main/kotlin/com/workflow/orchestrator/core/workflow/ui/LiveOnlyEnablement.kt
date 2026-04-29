package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.swing.JComponent

/**
 * Binds a set of Swing controls' `isEnabled` state to `WorkflowContextService.interactionMode`.
 * Disables the controls when the panel enters `ReadOnly` mode (focused PR's source branch
 * is not the currently checked-out branch on the PR's repo — line numbers don't match, so
 * line-anchored interactions are unsafe).
 *
 * Each disabled control gets an explanatory tooltip naming the focused PR's source branch
 * so users can switch and re-enable. The check is grounded in the PR's repo's VCS state
 * (same data IntelliJ's branch widget reads), not the editor's open file.
 *
 * Spec §7.2 + §7.3 (live-only enumeration).
 *
 * @param parent owner Disposable; the subscription dies when [parent] is disposed.
 * @param service the canonical [WorkflowContextService] (one per project).
 * @param controls Swing components to gate on `Live`.
 */
fun bindLiveOnlyEnablement(
    parent: Disposable,
    service: WorkflowContextService,
    vararg controls: JComponent,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    Disposer.register(parent) { scope.cancel() }
    scope.launch {
        // Combined flow so the tooltip uses the same snapshot as the enablement decision.
        service.state
            .map { Pair(it.interactionMode, it.focusPr?.fromBranch) }
            .distinctUntilChanged()
            .collect { (mode, focusFromBranch) ->
                val live = (mode == InteractionMode.Live)
                controls.forEach { ctrl ->
                    ctrl.isEnabled = live
                    ctrl.toolTipText = if (live) null
                    else "Disabled: focused PR is on a different branch. Switch to ${focusFromBranch ?: "<none>"} to enable."
                }
            }
    }
}

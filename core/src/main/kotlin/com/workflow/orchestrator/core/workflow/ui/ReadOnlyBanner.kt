package com.workflow.orchestrator.core.workflow.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Amber banner shown across Build / Quality / PrDetail when the focused PR is on a
 * branch other than the editor's active branch (spec §7.1). Only visible when
 * `WorkflowContextService.interactionModeFlow` is `ReadOnly`.
 *
 * Two link actions:
 * - "Switch branch" — placeholder; T15 wires it to a branch-checkout flow if the
 *   underlying VCS supports it. Currently no-op.
 * - "Clear PR focus" — calls `service.focusPr(null)` to drop the focus and return
 *   to Live mode.
 *
 * Owner registers as child Disposable: `Disposer.register(parent, banner)`. The
 * banner's coroutine scope dies when the parent is disposed.
 */
class ReadOnlyBanner(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = WorkflowContextService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    private val message = JBLabel("")
    private val switchBranchLink = ActionLink("Switch branch") {
        // Off-EDT: dirty-tree guard + GitBrancher.checkout. The link callback fires on EDT;
        // we hand off immediately. See BranchSwitchAction for the single-repo semantics
        // (only the resolved/selected module's repo is touched) and dirty-tree contract.
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                BranchSwitchAction.trySwitch(project)
            }
        }
    }
    private val clearFocusLink = ActionLink("Clear PR focus") {
        scope.launch { service.focusPr(null) }
    }

    init {
        background = StatusColors.WARNING_BG
        border = JBUI.Borders.empty(3, 8)
        isVisible = false

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(message)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(switchBranchLink)
            add(clearFocusLink)
        }
        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)

        scope.launch {
            service.state
                .map { it.interactionMode }
                .distinctUntilChanged()
                .collect { mode ->
                    val readOnly = (mode == InteractionMode.ReadOnly)
                    isVisible = readOnly
                    if (readOnly) updateMessage()
                    revalidate()
                    repaint()
                }
        }
    }

    private fun updateMessage() {
        val s = service.state.value
        val pr = s.focusPr ?: return
        // The branch shown here is the PR's repo's actual checked-out branch — looked
        // up via GitRepositoryManager, NOT derived from whichever file the user has
        // open in the editor. So opening a random .txt file in another submodule
        // never affects this message.
        val branch = s.prRepoBranch ?: "branch unknown"
        message.text = "Viewing PR #${pr.prId} (${pr.fromBranch}). The PR's repo is on $branch — interactions disabled."
    }

    override fun dispose() {
        scope.cancel()
    }
}

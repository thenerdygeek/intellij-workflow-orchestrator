package com.workflow.orchestrator.handover.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBTabbedPane
import com.workflow.orchestrator.handover.model.HandoverState
import com.workflow.orchestrator.handover.service.HandoverStateService
import com.workflow.orchestrator.handover.ui.tabs.ActionsTab
import com.workflow.orchestrator.handover.ui.tabs.ChecksTab
import com.workflow.orchestrator.handover.ui.tabs.ShareTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Top-level Handover tab panel.
 *
 * Layout:
 * ```
 * BorderLayout:
 *   NORTH = HandoverTicketHeader
 *   CENTER = JPanel(BorderLayout):
 *     NORTH = HandoverOverrideBanner   (persistent amber banner)
 *     CENTER = JBTabbedPane(Checks | Actions | Share)
 * ```
 *
 * The panel owns the single state-flow collector and fans state out to:
 *   - [HandoverTicketHeader.updateState]
 *   - [ChecksTab.updateState]
 *   - [HandoverOverrideBanner.setFailures]
 *
 * The Actions and Share tabs subscribe to their own state internally — their cards
 * (CopyrightFixCard, TimeLogCard, TemplateEditorCard) do their own bookkeeping.
 *
 * Default tab heuristic on construction:
 *   - any failed check → select **Checks** tab
 *   - all green → select **Share** tab
 */
class HandoverPanel private constructor(
    private val project: Project,
    private val stateFlow: StateFlow<HandoverState>,
    private val header: HandoverTicketHeader,
    private val banner: HandoverOverrideBanner,
    private val checksTab: ChecksTab,
    private val actionsTab: JComponent,
    private val shareTab: JComponent,
    private val scope: CoroutineScope,
    private val edtDispatcher: kotlin.coroutines.CoroutineContext,
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(HandoverPanel::class.java)
    private val tabs = JBTabbedPane()

    init {
        tabs.addTab("Checks", checksTab)
        tabs.addTab("Actions", actionsTab)
        tabs.addTab("Share", shareTab)

        // Build the layout: header on top, banner-above-tabs in center.
        val center = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(banner, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)

        // Cascade dispose for child tabs that own coroutine scopes / cards.
        if (checksTab is Disposable) Disposer.register(this, checksTab)
        if (actionsTab is Disposable) Disposer.register(this, actionsTab as Disposable)
        if (shareTab is Disposable) Disposer.register(this, shareTab as Disposable)

        // Apply the initial state synchronously so the default-tab heuristic and the
        // banner reflect whatever HandoverStateService already has on construction.
        applyState(stateFlow.value)
        tabs.selectedIndex = if (failedFromState(stateFlow.value).isNotEmpty()) 0 else 2

        // Single state-flow collector — fan out to header / checks / banner.
        // We `drop(1)` because the initial replay is already applied synchronously above.
        scope.launch {
            stateFlow.drop(1).collect { state ->
                withContext(edtDispatcher) {
                    applyState(state)
                }
            }
        }
    }

    private fun applyState(state: HandoverState) {
        log.info(
            "[Handover:Panel] applyState ticket=${state.ticketId} " +
                "prCreated=${state.prCreated} build=${state.buildStatus?.let { "${it.planKey}#${it.buildNumber}:${it.status}" } ?: "—"} " +
                "quality=${state.qualityGatePassed} health=${state.healthCheckPassed} suites=${state.suiteResults.size} " +
                "copyrightFixed=${state.copyrightFixed} jiraComment=${state.jiraCommentPosted} workLogged=${state.todayWorkLogged} " +
                "failedChecks=${failedFromState(state).size}"
        )
        header.updateState(state)
        checksTab.updateState(state)
        banner.setFailures(failedFromState(state))
        // Wire the active ticket into TimeLogCard so the Log Work button becomes enabled.
        // The cast is safe: production always uses ActionsTab; tests use a plain JPanel stub
        // (in which case the cast returns null and the call is silently skipped).
        (actionsTab as? ActionsTab)?.updateTicket(
            ticketKey = state.ticketId.takeIf { it.isNotBlank() },
            startWorkTimestamp = state.startWorkTimestamp,
        )
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {

        /**
         * Production constructor — wires real services, builds the three real tab
         * components, and starts a state-flow collector on a fresh coroutine scope.
         */
        operator fun invoke(project: Project): HandoverPanel {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val stateFlow = HandoverStateService.getInstance(project).stateFlow
            val header = HandoverTicketHeader()
            val banner = HandoverOverrideBanner(project)
            val checksTab = ChecksTab(project)
            val actionsTab = ActionsTab(project)
            val shareTab = ShareTab.create(project)
            return HandoverPanel(
                project = project,
                stateFlow = stateFlow,
                header = header,
                banner = banner,
                checksTab = checksTab,
                actionsTab = actionsTab,
                shareTab = shareTab,
                scope = scope,
                edtDispatcher = Dispatchers.EDT,
            )
        }

        /**
         * Test factory — accepts pre-built widgets and a state flow so unit tests can
         * exercise layout / default-tab / banner behavior without having to spin up
         * the ShareTab and ActionsTab service graphs.
         */
        @TestOnly
        fun forTest(
            project: Project,
            stateFlow: StateFlow<HandoverState>,
            header: HandoverTicketHeader,
            banner: HandoverOverrideBanner,
            checksTab: ChecksTab,
            actionsTab: JComponent,
            shareTab: JComponent,
            scope: CoroutineScope,
            edtDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.Unconfined,
        ): HandoverPanel = HandoverPanel(
            project = project,
            stateFlow = stateFlow,
            header = header,
            banner = banner,
            checksTab = checksTab,
            actionsTab = actionsTab,
            shareTab = shareTab,
            scope = scope,
            edtDispatcher = edtDispatcher,
        )

        /**
         * Compute the failed/in-progress check list for the override banner from the
         * canonical [HandoverState]. Mirrors the gating logic ShareTab uses internally.
         */
        internal fun failedFromState(state: HandoverState): List<FailedCheck> {
            val out = mutableListOf<FailedCheck>()
            if (state.qualityGatePassed == false) {
                out += FailedCheck("quality.gate", "Quality gate FAILED", "Quality")
            }
            state.suiteResults.forEach { suite ->
                val key = suite.suitePlanKey
                when (suite.passed) {
                    false -> out += FailedCheck(
                        id = "suite.${key.lowercase()}",
                        label = "$key: FAIL",
                        targetTab = "Automation",
                    )
                    null -> out += FailedCheck(
                        id = "suite.${key.lowercase()}.running",
                        label = "$key: running",
                        targetTab = "Automation",
                    )
                    true -> Unit
                }
            }
            return out
        }
    }
}

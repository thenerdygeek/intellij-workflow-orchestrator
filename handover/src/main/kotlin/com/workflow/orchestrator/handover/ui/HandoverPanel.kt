package com.workflow.orchestrator.handover.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.workflow.orchestrator.handover.service.HandoverStateService
import com.workflow.orchestrator.handover.service.JiraClosureService
import com.workflow.orchestrator.handover.service.QaClipboardService
import com.workflow.orchestrator.handover.ui.cards.CopyrightFixCard
import com.workflow.orchestrator.handover.ui.panels.JiraCommentPanel
import com.workflow.orchestrator.handover.ui.panels.QaClipboardPanel
import com.workflow.orchestrator.handover.ui.cards.TimeLogCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

class HandoverPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateService = HandoverStateService.getInstance(project)
    private val closureService = JiraClosureService.getInstance(project)
    private val qaClipboardService = QaClipboardService.getInstance(project)

    // UI components
    private val contextPanel = HandoverContextPanel()
    private val cardLayout = CardLayout()
    private val detailContainer = JPanel(cardLayout)
    private val toolbar: HandoverToolbar

    // Detail panels
    private val copyrightCard = CopyrightFixCard(project)
    private val jiraCommentPanel = JiraCommentPanel(project)
    private val timeLogCard = TimeLogCard(project)
    private val qaClipboardPanel = QaClipboardPanel(project)

    init {
        toolbar = HandoverToolbar { panelId -> switchPanel(panelId) }

        // Register detail panels in card layout
        detailContainer.add(copyrightCard, HandoverToolbar.PANEL_COPYRIGHT)
        detailContainer.add(jiraCommentPanel, HandoverToolbar.PANEL_JIRA)
        detailContainer.add(timeLogCard, HandoverToolbar.PANEL_TIME)
        detailContainer.add(qaClipboardPanel, HandoverToolbar.PANEL_QA)

        // Splitter: left context (30%) + right detail (70%)
        val splitter = JBSplitter(false, 0.30f).apply {
            firstComponent = contextPanel
            secondComponent = detailContainer
        }

        add(toolbar.createToolbar(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // CopyrightFixCard (Phase 2) and TimeLogCard (Phase 3) both own a
        // coroutine scope and need to be disposed when this panel goes away.
        Disposer.register(this, copyrightCard)
        Disposer.register(this, timeLogCard)

        // Single state-flow collector fans out to all panels that are wired.
        // Phase 3/4 panels plug in here — only add their wiring calls inside this collect.
        scope.launch {
            stateService.stateFlow.collect { state ->
                // Context sidebar (left) — always updated
                withContext(Dispatchers.EDT) {
                    contextPanel.updateState(state)
                }

                // Jira Comment panel — Phase 1
                val commentText = if (state.suiteResults.isEmpty()) {
                    ""
                } else {
                    closureService.buildClosureComment(state.suiteResults)
                }
                withContext(Dispatchers.EDT) {
                    jiraCommentPanel.updateFromState(state.ticketId, commentText)
                }

                // QA Clipboard panel — Phase 2
                val ticketIds = listOfNotNull(state.ticketId.takeIf { it.isNotBlank() })
                val payload = qaClipboardService.buildPayloadFromSuiteResults(state.suiteResults, ticketIds)
                val formatted = qaClipboardService.formatForClipboard(payload)
                withContext(Dispatchers.EDT) {
                    qaClipboardPanel.setDockerTags(payload.dockerTags)
                    qaClipboardPanel.setFormattedText(formatted)
                }

                // Time Log panel — Phase 3
                withContext(Dispatchers.EDT) {
                    timeLogCard.setTicket(state.ticketId.takeIf { it.isNotBlank() })
                    timeLogCard.setStartedTimestamp(state.startWorkTimestamp)
                }
            }
        }

        // Show copyright panel by default (first in workflow)
        switchPanel(HandoverToolbar.PANEL_COPYRIGHT)
    }

    private fun switchPanel(panelId: String) {
        cardLayout.show(detailContainer, panelId)
    }

    override fun dispose() {
        scope.cancel()
    }
}

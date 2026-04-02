package com.workflow.orchestrator.handover.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.handover.service.HandoverStateService
import com.workflow.orchestrator.handover.ui.panels.*
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

class HandoverPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(Dispatchers.EDT + SupervisorJob())
    private val stateService = HandoverStateService.getInstance(project)

    // UI components
    private val contextPanel = HandoverContextPanel()
    private val cardLayout = CardLayout()
    private val detailContainer = JPanel(cardLayout)
    private val toolbar: HandoverToolbar

    // Detail panels
    private val copyrightPanel = CopyrightPanel(project)
    private val preReviewPanel = PreReviewPanel(project)
    private val jiraCommentPanel = JiraCommentPanel(project)
    private val timeLogPanel = TimeLogPanel(project)
    private val qaClipboardPanel = QaClipboardPanel(project)
    private val completionMacroPanel = CompletionMacroPanel(project)

    init {
        toolbar = HandoverToolbar { panelId -> switchPanel(panelId) }

        // Register detail panels in card layout
        detailContainer.add(copyrightPanel, HandoverToolbar.PANEL_COPYRIGHT)
        detailContainer.add(preReviewPanel, HandoverToolbar.PANEL_AI_REVIEW)
        detailContainer.add(jiraCommentPanel, HandoverToolbar.PANEL_JIRA)
        detailContainer.add(timeLogPanel, HandoverToolbar.PANEL_TIME)
        detailContainer.add(qaClipboardPanel, HandoverToolbar.PANEL_QA)
        detailContainer.add(completionMacroPanel, HandoverToolbar.PANEL_MACRO)

        // Splitter: left context (30%) + right detail (70%)
        val splitter = JBSplitter(false, 0.30f).apply {
            firstComponent = contextPanel
            secondComponent = detailContainer
        }

        add(toolbar.createToolbar(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        // Bind to state flow
        scope.launch {
            stateService.stateFlow.collect { state ->
                contextPanel.updateState(state)
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

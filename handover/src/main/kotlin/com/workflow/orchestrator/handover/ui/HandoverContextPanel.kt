package com.workflow.orchestrator.handover.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.handover.model.HandoverState
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

class HandoverContextPanel : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    // Section labels — updated reactively
    private val ticketIdLabel = JBLabel("")
    private val ticketSummaryLabel = JBLabel("")
    private val ticketStatusLabel = JBLabel("")
    private val transitionComboBox = com.intellij.openapi.ui.ComboBox<String>()
    private val transitionButton = javax.swing.JButton("Transition").apply {
        isEnabled = false
        toolTipText = "Coming soon"
    }
    private val prStatusLabel = JBLabel("")
    private val buildStatusLabel = JBLabel("")
    private val qualityLabel = JBLabel("")
    private val dockerTagLabel = JBLabel("")
    private val suiteSectionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val actionsSectionPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        buildLayout()
    }

    private fun buildLayout() {
        contentPanel.add(sectionHeader("Current Ticket"))
        contentPanel.add(ticketIdLabel)
        contentPanel.add(ticketSummaryLabel)
        contentPanel.add(ticketStatusLabel)
        val transitionRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
            add(transitionComboBox)
            add(transitionButton)
        }
        contentPanel.add(transitionRow)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Pull Request"))
        contentPanel.add(prStatusLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Bamboo Builds"))
        contentPanel.add(buildStatusLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Quality Gate"))
        contentPanel.add(qualityLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Docker Tag"))
        contentPanel.add(dockerTagLabel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Automation Suites"))
        contentPanel.add(suiteSectionPanel)
        contentPanel.add(separator())

        contentPanel.add(sectionHeader("Actions Done"))
        contentPanel.add(actionsSectionPanel)

        val scrollPane = JBScrollPane(contentPanel)
        scrollPane.border = JBUI.Borders.empty()
        add(scrollPane, BorderLayout.CENTER)
    }

    fun updateState(state: HandoverState) {
        ticketIdLabel.text = state.ticketId.ifEmpty { "No active ticket" }
        ticketSummaryLabel.text = state.ticketSummary
        ticketStatusLabel.text = "Status: ${state.currentStatusName ?: "Unknown"}"

        prStatusLabel.text = if (state.prCreated) {
            "PR created"
        } else {
            "No PR yet"
        }
        prStatusLabel.icon = if (state.prCreated) AllIcons.General.InspectionsOK else null

        buildStatusLabel.text = state.buildStatus?.let { build ->
            "${build.planKey} #${build.buildNumber} — ${build.status.name}"
        } ?: "No build data"
        buildStatusLabel.icon = when (state.buildStatus?.status) {
            WorkflowEvent.BuildEventStatus.SUCCESS -> AllIcons.General.InspectionsOK
            WorkflowEvent.BuildEventStatus.FAILED -> AllIcons.General.Error
            null -> null
        }

        qualityLabel.text = when (state.qualityGatePassed) {
            true -> "Quality gate: PASSED"
            false -> "Quality gate: FAILED"
            null -> "Quality gate: Unknown"
        }
        qualityLabel.icon = when (state.qualityGatePassed) {
            true -> AllIcons.General.InspectionsOK
            false -> AllIcons.General.Error
            null -> null
        }

        // Docker tags — show first tag from latest suite
        val latestSuite = state.suiteResults.lastOrNull()
        dockerTagLabel.text = latestSuite?.dockerTagsJson?.take(50) ?: "No docker tags"

        // Automation suites
        suiteSectionPanel.removeAll()
        if (state.suiteResults.isEmpty()) {
            suiteSectionPanel.add(JBLabel("No suites run"))
        } else {
            for (suite in state.suiteResults) {
                val icon = when (suite.passed) {
                    true -> AllIcons.General.InspectionsOK
                    false -> AllIcons.General.Error
                    null -> AllIcons.Process.Step_1
                }
                val statusText = when (suite.passed) {
                    true -> "PASS"
                    false -> "FAIL"
                    null -> "running"
                }
                suiteSectionPanel.add(JBLabel("${suite.suitePlanKey}: $statusText", icon, SwingConstants.LEFT))
            }
        }

        // Actions done
        actionsSectionPanel.removeAll()
        actionsSectionPanel.add(actionLabel("Copyright fixed", state.copyrightFixed))
        actionsSectionPanel.add(actionLabel("PR created", state.prCreated))
        actionsSectionPanel.add(actionLabel("Jira comment", state.jiraCommentPosted))
        actionsSectionPanel.add(actionLabel("Jira transitioned", state.jiraTransitioned))
        actionsSectionPanel.add(actionLabel("Time logged", state.todayWorkLogged))

        revalidate()
        repaint()
    }

    fun setTransitions(transitions: List<String>) {
        transitionComboBox.removeAllItems()
        transitions.forEach { transitionComboBox.addItem(it) }
    }

    fun getSelectedTransition(): String? {
        return transitionComboBox.selectedItem as? String
    }

    private fun actionLabel(text: String, done: Boolean): JBLabel {
        val icon = if (done) AllIcons.General.InspectionsOK else AllIcons.RunConfigurations.TestNotRan
        val prefix = if (done) "✓" else "○"
        return JBLabel("$prefix $text", icon, SwingConstants.LEFT)
    }

    private fun sectionHeader(text: String): JBLabel {
        return JBLabel(text).apply {
            font = font.deriveFont(java.awt.Font.BOLD)
            border = JBUI.Borders.emptyTop(4)
        }
    }

    private fun separator(): JSeparator {
        return JSeparator().apply {
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 1)
        }
    }
}

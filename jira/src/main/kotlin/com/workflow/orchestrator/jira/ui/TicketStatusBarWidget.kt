package com.workflow.orchestrator.jira.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.ui.components.JBLabel
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel

class TicketStatusBarWidget(
    project: Project
) : EditorBasedWidget(project), StatusBarWidget.TextPresentation {

    private val log = Logger.getInstance(TicketStatusBarWidget::class.java)

    companion object {
        const val ID = "WorkflowTicketStatusBar"
    }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        log.info("[Jira:StatusBar] Widget installed")
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        return if (!ticketId.isNullOrBlank()) ticketId else "Workflow: Idle"
    }

    override fun getTooltipText(): String {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        val summary = settings.state.activeTicketSummary
        return if (!ticketId.isNullOrBlank()) "$ticketId: $summary" else "No active ticket"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        showPopup(event.component)
    }

    private fun showPopup(component: Component) {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        val summary = settings.state.activeTicketSummary

        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints().apply {
            gridx = 0; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            anchor = GridBagConstraints.NORTHWEST
        }

        var row = 0
        if (!ticketId.isNullOrBlank()) {
            gbc.gridy = row++
            panel.add(JBLabel("<html><b>Active Ticket:</b> $ticketId</html>"), gbc)

            if (!summary.isNullOrBlank()) {
                gbc.gridy = row++
                gbc.insets = JBUI.insets(2, 0)
                panel.add(JBLabel(summary), gbc)
            }

            val currentBranch = tryGetCurrentBranch()
            if (currentBranch != null) {
                gbc.gridy = row++
                gbc.insets = JBUI.insets(4, 0, 0, 0)
                panel.add(JBLabel("<html><b>Branch:</b> $currentBranch</html>"), gbc)
            }
        } else {
            gbc.gridy = row++
            panel.add(JBLabel("No active ticket"), gbc)
        }

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setTitle("Workflow Status")
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .createPopup()
            .showUnderneathOf(component)
    }

    fun update() {
        myStatusBar?.updateWidget(ID())
    }

    private fun tryGetCurrentBranch(): String? {
        return try {
            val gitRepoClass = Class.forName("git4idea.repo.GitRepositoryManager")
            val getInstance = gitRepoClass.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val repos = gitRepoClass.getMethod("getRepositories").invoke(manager) as? List<*>
            val firstRepo = repos?.firstOrNull() ?: return null
            firstRepo.javaClass.getMethod("getCurrentBranchName").invoke(firstRepo) as? String
        } catch (_: Exception) {
            null
        }
    }
}

package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.insights.GenerateReportAction
import com.workflow.orchestrator.core.polling.SmartPoller
import com.workflow.orchestrator.core.services.InsightsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTabbedPane

class InsightsPanel(
    private val project: Project,
    private val service: InsightsService,
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val todayPanel = TodayPanel()
    private val weekPanel = WeekPanel()
    private val sessionsPanel = SessionsPanel()
    private val reliabilityPanel = ReliabilityPanel(project)

    private val tabs = JBTabbedPane(JTabbedPane.TOP).apply {
        addTab("Today", todayPanel)
        addTab("This Week", weekPanel)
        addTab("Sessions", sessionsPanel)
        addTab("Reliability", reliabilityPanel)
    }

    @Volatile private var lastApplied: InsightsSnapshot? = null

    private val poller = SmartPoller(
        name = "insights",
        baseIntervalMs = 30_000,
        maxIntervalMs = 300_000,
        scope = scope,
        action = {
            val snapshot = loadSnapshot()
            val changed = snapshot != lastApplied
            if (changed) {
                lastApplied = snapshot
                invokeLater { applySnapshot(snapshot) }
            }
            changed   // real signal: backoff decays to 300s while nothing changes
        }
    )

    init {
        border = JBUI.Borders.empty()
        add(buildToolbar(), BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        scope.launch {
            val eventBus = project.getService(EventBus::class.java)
            eventBus.events.collect { event ->
                if (event is WorkflowEvent.TaskChanged) {
                    val snapshot = loadSnapshot()
                    lastApplied = snapshot
                    invokeLater { applySnapshot(snapshot) }
                }
            }
        }

        addAncestorListener(object : javax.swing.event.AncestorListener {
            override fun ancestorAdded(e: javax.swing.event.AncestorEvent) { poller.start() }
            override fun ancestorRemoved(e: javax.swing.event.AncestorEvent) { poller.stop() }
            override fun ancestorMoved(e: javax.swing.event.AncestorEvent) {}
        })
    }

    fun refresh() {
        scope.launch {
            val snapshot = loadSnapshot()
            lastApplied = snapshot
            invokeLater { applySnapshot(snapshot) }
        }
    }

    override fun dispose() {
        // Idempotently stop the poller (AncestorListener may have already done so on
        // soft tab-hide); cancel the scope so the EventBus collector exits and any
        // in-flight launch is interrupted on hard project close. Wired by
        // WorkflowToolWindowFactory via content.setDisposer(panel).
        poller.stop()
        scope.cancel()
    }

    private fun loadSnapshot(): InsightsSnapshot {
        val overview = service.getOverview()
        return InsightsSnapshot(overview.today, overview.week, overview.sessions)
    }

    private fun applySnapshot(snapshot: InsightsSnapshot) {
        todayPanel.update(snapshot.today)
        weekPanel.update(snapshot.week, snapshot.all)
        sessionsPanel.update(snapshot.all)
    }

    private fun buildToolbar(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2))
        panel.border = JBUI.Borders.emptyBottom(2)

        val refreshBtn = JButton("Refresh").apply {
            addActionListener { refresh() }
            isFocusable = false
        }

        val reportBtn = JButton("Generate Report ▸").apply {
            toolTipText = "Generate an AI-powered insights report"
            isFocusable = false
            addActionListener {
                // Use ActionUtil.invokeAction(action, event, onDone) to trigger the action
                // through the platform instead of calling actionPerformed directly
                // (@ApiStatus.OverrideOnly — must not be invoked from client code).
                val action = GenerateReportAction()
                val dataContext = com.intellij.ide.DataManager.getInstance().getDataContext(this@InsightsPanel)
                val event = AnActionEvent.createEvent(action, dataContext, action.templatePresentation.clone(), ActionPlaces.TOOLWINDOW_CONTENT, ActionUiKind.NONE, null)
                ActionUtil.invokeAction(action, event, null)
            }
        }

        panel.add(JBLabel(""))
        panel.add(refreshBtn)
        panel.add(reportBtn)
        return panel
    }
}

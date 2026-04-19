package com.workflow.orchestrator.core.toolwindow.insights

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
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
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTabbedPane

class InsightsPanel(
    private val project: Project,
    private val service: InsightsService,
) : JPanel(BorderLayout()) {

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

    private val poller = SmartPoller(
        name = "insights",
        baseIntervalMs = 30_000,
        maxIntervalMs = 300_000,
        scope = scope,
        action = {
            val snapshot = loadSnapshot()
            invokeLater { applySnapshot(snapshot) }
            true
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
            invokeLater { applySnapshot(snapshot) }
        }
    }

    private fun loadSnapshot(): InsightsSnapshot {
        val today = service.getTodayStats()
        val week = service.getWeekStats()
        val all = service.getSessions()
        return InsightsSnapshot(today, week, all)
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
                val dataContext = com.intellij.ide.DataManager.getInstance().getDataContext(this@InsightsPanel)
                val action = GenerateReportAction()
                val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.TOOLWINDOW_CONTENT, dataContext)
                action.actionPerformed(event)
            }
        }

        panel.add(JBLabel(""))
        panel.add(refreshBtn)
        panel.add(reportBtn)
        return panel
    }
}

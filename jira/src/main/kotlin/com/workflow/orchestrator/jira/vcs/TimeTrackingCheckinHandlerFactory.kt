package com.workflow.orchestrator.jira.vcs

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.intellij.openapi.progress.runBackgroundableTask
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Adds a time tracking panel to the commit dialog where developers
 * can log work time against the active Jira ticket.
 */
class TimeTrackingCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return TimeTrackingCheckinHandler(panel.project)
    }
}

class TimeTrackingCheckinHandler(private val project: Project) : CheckinHandler() {

    private val log = Logger.getInstance(TimeTrackingCheckinHandler::class.java)
    private val credentialStore = CredentialStore()

    private var logTimeCheckbox: JBCheckBox? = null
    private var minutesSpinner: JSpinner? = null

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent? {
        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        if (ticketId.isNullOrBlank()) return null

        val startTimestamp = settings.state.startWorkTimestamp
        val maxHours = settings.state.maxWorklogHours
        val incrementHours = settings.state.worklogIncrementHours

        val now = System.currentTimeMillis()
        val elapsedMinutes = TimeTrackingLogic.elapsedMinutes(startTimestamp, now)
        val clampedMinutes = TimeTrackingLogic.clampMinutes(elapsedMinutes, maxHours)
        val incrementMinutes = (incrementHours * 60).toInt().coerceAtLeast(1)
        val maxMinutes = (maxHours * 60).toInt()

        val checkbox = JBCheckBox("Log time to $ticketId", true)
        logTimeCheckbox = checkbox

        val spinnerModel = SpinnerNumberModel(
            clampedMinutes,     // value
            0,                  // minimum
            maxMinutes,         // maximum
            incrementMinutes    // step
        )
        val spinner = JSpinner(spinnerModel)
        minutesSpinner = spinner

        val displayLabel = javax.swing.JLabel(TimeTrackingLogic.formatJiraTime(clampedMinutes))
        spinner.addChangeListener {
            val mins = spinner.value as Int
            displayLabel.text = TimeTrackingLogic.formatJiraTime(mins)
        }

        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        panel.border = JBUI.Borders.empty(4)
        panel.add(checkbox, BorderLayout.WEST)

        val spinnerPanel = JPanel(BorderLayout(JBUI.scale(4), 0))
        spinnerPanel.add(spinner, BorderLayout.CENTER)
        spinnerPanel.add(displayLabel, BorderLayout.EAST)
        panel.add(spinnerPanel, BorderLayout.CENTER)

        return object : RefreshableOnComponent {
            override fun getComponent(): JComponent = panel
            override fun refresh() {}
            override fun saveState() {}
            override fun restoreState() {}
        }
    }

    override fun checkinSuccessful() {
        val checkbox = logTimeCheckbox ?: return
        if (!checkbox.isSelected) return

        val minutes = (minutesSpinner?.value as? Int) ?: return
        if (minutes <= 0) return

        val settings = PluginSettings.getInstance(project)
        val ticketId = settings.state.activeTicketId
        if (ticketId.isNullOrBlank()) return

        val baseUrl = settings.connections.jiraUrl.orEmpty().trimEnd('/')
        if (baseUrl.isBlank()) return

        val timeSpent = TimeTrackingLogic.toJiraTimeSpent(minutes)

        runBackgroundableTask("Logging time to $ticketId", project, false) {
            try {
                val client = JiraApiClient(baseUrl) { credentialStore.getToken(ServiceType.JIRA) }
                val result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    client.postWorklog(ticketId, timeSpent)
                }
                when (result) {
                    is com.workflow.orchestrator.core.model.ApiResult.Success ->
                        log.info("[Jira:TimeTracking] Logged $timeSpent to $ticketId")
                    is com.workflow.orchestrator.core.model.ApiResult.Error ->
                        log.warn("[Jira:TimeTracking] Failed to log time to $ticketId: ${result.message}")
                }
            } catch (e: Exception) {
                log.warn("[Jira:TimeTracking] Error logging time to $ticketId: ${e.message}")
            }
        }
    }
}

/** Pure logic — testable without IntelliJ dependencies. */
object TimeTrackingLogic {

    /**
     * Formats minutes into a human-readable display format: "2h 30m".
     * Always shows both hours and minutes components.
     */
    fun formatJiraTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return "${h}h ${m}m"
    }

    /**
     * Calculates elapsed minutes between a start timestamp and now.
     * Returns 0 if startTimestampMs is 0 (no start time recorded).
     */
    fun elapsedMinutes(startTimestampMs: Long, nowMs: Long): Int {
        if (startTimestampMs <= 0L) return 0
        val diffMs = nowMs - startTimestampMs
        return (diffMs / (60 * 1000)).toInt().coerceAtLeast(0)
    }

    /**
     * Clamps minutes to a maximum number of hours.
     */
    fun clampMinutes(minutes: Int, maxHours: Float): Int {
        val maxMinutes = (maxHours * 60).toInt()
        return minutes.coerceAtMost(maxMinutes)
    }

    /**
     * Builds a Jira worklog time-spent string.
     * - "2h 30m" when both hours and minutes
     * - "1h" when only hours
     * - "30m" when only minutes
     * - "0m" when zero
     */
    fun toJiraTimeSpent(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}

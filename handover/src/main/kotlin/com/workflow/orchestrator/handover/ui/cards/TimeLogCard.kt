package com.workflow.orchestrator.handover.ui.cards

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.handover.ui.panels.handoverPanelHeader
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.handover.service.HandoverStateService
import com.workflow.orchestrator.handover.service.TimeTrackingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class TimeLogCard(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(TimeLogCard::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeService = TimeTrackingService.getInstance(project)

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val emptyLabel = JBLabel("Select a ticket to log time.").apply {
        foreground = StatusColors.SECONDARY_TEXT
        horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.emptyTop(40)
    }

    val ticketLabel = JBLabel("")
    val dateField = JBTextField(LocalDate.now().toString(), 10)
    val hoursField = JBTextField("1.0", 5)
    private val decrementButton = JButton("-").apply {
        addActionListener { adjustHours(-HOURS_STEP) }
    }
    private val incrementButton = JButton("+").apply {
        addActionListener { adjustHours(HOURS_STEP) }
    }
    val commentField = JBTextField()
    val elapsedHintLabel = JBLabel("").apply {
        foreground = StatusColors.SECONDARY_TEXT
    }
    val logButton = JButton("Log Work").apply {
        isEnabled = false
        toolTipText = "Select a Jira ticket in the Sprint tab first"
    }
    val statusLabel = JBLabel("")

    @Volatile
    private var activeTicketId: String = ""

    @Volatile
    private var startWorkTimestamp: Long = 0L

    init {
        border = JBUI.Borders.empty(8)

        val formPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        formPanel.add(JBLabel("Ticket:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(ticketLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        formPanel.add(JBLabel("Date (yyyy-MM-dd):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(dateField, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        formPanel.add(JBLabel("Hours (max 7):"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val hoursStepper = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            add(decrementButton)
            add(hoursField)
            add(incrementButton)
        }
        formPanel.add(hoursStepper, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0
        formPanel.add(JBLabel("Comment:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        formPanel.add(commentField, gbc)

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.weightx = 1.0
        formPanel.add(elapsedHintLabel, gbc)

        val southPanel = JPanel(BorderLayout()).apply {
            add(logButton, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }

        cardPanel.add(formPanel, "form")
        cardPanel.add(emptyLabel, "empty")
        cardLayout.show(cardPanel, "empty")

        add(handoverPanelHeader("TIME TRACKING"), BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(southPanel, BorderLayout.SOUTH)

        // Re-evaluate the Log button on every relevant edit so the user gets
        // immediate visual feedback (no stale enabled state).
        val refreshOnEdit = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshLogButtonState()
            override fun removeUpdate(e: DocumentEvent?) = refreshLogButtonState()
            override fun changedUpdate(e: DocumentEvent?) = refreshLogButtonState()
        }
        hoursField.document.addDocumentListener(refreshOnEdit)
        dateField.document.addDocumentListener(refreshOnEdit)

        logButton.addActionListener { onLogClicked() }
    }

    /** Show the form when a ticket is selected, or empty state when cleared. */
    fun setTicket(ticketKey: String?) {
        activeTicketId = ticketKey.orEmpty()
        if (ticketKey.isNullOrBlank()) {
            cardLayout.show(cardPanel, "empty")
        } else {
            ticketLabel.text = ticketKey
            cardLayout.show(cardPanel, "form")
        }
        refreshLogButtonState()
    }

    /**
     * Updates the "Suggested: Nh (since Start Work)" hint and remembers the
     * timestamp so the user can pre-fill via clicking the hint label later
     * (out of scope v1; the suggestion is informational only).
     */
    fun setStartedTimestamp(timestamp: Long) {
        startWorkTimestamp = timestamp
        if (timestamp <= 0L) {
            elapsedHintLabel.text = ""
            return
        }
        val raw = timeService.computeElapsedHours(timestamp, System.currentTimeMillis())
        val clamped = timeService.clampHours(raw)
        // Round to nearest 0.25h for display so "Suggested: 3.7h" doesn't read noisy.
        val display = (Math.round(clamped * 4.0) / 4.0)
        elapsedHintLabel.text = "Suggested: ${display}h (since Start Work)"
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun adjustHours(delta: Double) {
        val current = hoursField.text.toDoubleOrNull() ?: DEFAULT_HOURS
        val maxHours = timeService.getMaxHours()
        hoursField.text = (current + delta).coerceIn(MIN_HOURS, maxHours).toString()
    }

    private fun refreshLogButtonState() {
        val ticketOk = activeTicketId.isNotBlank()
        val hours = hoursField.text.toDoubleOrNull()
        val hoursOk = hours != null && timeService.validateHours(hours)
        val date = parseDateOrNull(dateField.text)
        val dateOk = date != null && !date.isAfter(LocalDate.now())

        val canLog = ticketOk && hoursOk && dateOk
        logButton.isEnabled = canLog
        logButton.toolTipText = when {
            !ticketOk -> "Select a Jira ticket in the Sprint tab first"
            hours == null -> "Enter hours as a decimal (e.g. 1.5)"
            !hoursOk -> "Hours must be between 0 and ${timeService.getMaxHours()}"
            date == null -> "Date must be yyyy-MM-dd"
            !dateOk -> "Cannot log a future date"
            else -> "Log work on $activeTicketId"
        }
    }

    private fun parseDateOrNull(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (_: DateTimeParseException) {
        null
    }

    private fun onLogClicked() {
        val ticketKey = activeTicketId
        val hours = hoursField.text.toDoubleOrNull()
        val date = parseDateOrNull(dateField.text)
        if (ticketKey.isBlank() || hours == null || date == null) return
        if (!timeService.validateHours(hours)) {
            statusLabel.text = "Hours must be between 0 and ${timeService.getMaxHours()}"
            return
        }
        if (date.isAfter(LocalDate.now())) {
            statusLabel.text = "Cannot log a future date"
            return
        }

        val timeSpent = timeService.hoursToJiraTimeString(hours)
        // PR 5 of the 2026-05-07 write-ops audit: the user-picked date is now passed
        // through to JiraService.logWork as an OffsetDateTime so the worklog's `started`
        // field reflects the user's intent — pre-PR-5 this was discarded and Jira fell
        // back to "now". 9:00 UTC matches the prior `formatStartedDate(.., 9, 0)` shape.
        val started: OffsetDateTime = LocalDateTime
            .of(date.year, date.monthValue, date.dayOfMonth, 9, 0, 0)
            .atOffset(ZoneOffset.UTC)
        val comment = commentField.text.takeIf { it.isNotBlank() }

        logButton.isEnabled = false
        statusLabel.text = "Logging..."
        log.info("[Handover:TimeLog] Logging $timeSpent on $ticketKey (started=$started)")

        scope.launch {
            val jiraService = project.getService(JiraService::class.java)
            if (jiraService == null) {
                withContext(Dispatchers.EDT) {
                    statusLabel.text = "Jira service unavailable"
                    refreshLogButtonState()
                }
                return@launch
            }
            val result = jiraService.logWork(
                key = ticketKey,
                timeSpent = timeSpent,
                comment = comment,
                started = started
            )
            withContext(Dispatchers.EDT) {
                if (result.isError) {
                    log.warn("[Handover:TimeLog] logWork failed: ${result.summary}")
                    statusLabel.text = result.summary.take(80)
                    refreshLogButtonState()
                } else {
                    statusLabel.text = "Logged $timeSpent"
                    HandoverStateService.getInstance(project).markWorkLogged()
                    // Keep the button disabled to discourage accidental double-logging;
                    // user must edit hours / re-pick ticket to re-enable.
                }
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        private const val DEFAULT_HOURS = 1.0
        private const val MIN_HOURS = 0.5
        private const val HOURS_STEP = 0.5
    }
}

package com.workflow.orchestrator.jira.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.jira.api.dto.DevStatusPullRequest
import com.workflow.orchestrator.jira.service.JiraServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Lazy-loaded dev status section for the ticket detail panel.
 *
 * Shows linked pull requests with status badges (OPEN/MERGED/DECLINED)
 * and clickable PR names that open in browser.
 */
class DevStatusSection(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(DevStatusSection::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        isOpaque = false
    }

    fun loadDevStatus(issueId: String) {
        removeAll()

        // Show loading placeholder
        val loadingPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(AnimatedIcon.Default()).apply {
                border = JBUI.Borders.empty(8)
            })
            add(JBLabel("Loading pull requests...").apply {
                foreground = StatusColors.SECONDARY_TEXT
            })
        }
        add(loadingPanel, BorderLayout.CENTER)
        revalidate()
        repaint()

        scope.launch {
            val jiraServiceImpl = JiraServiceImpl.getInstance(project)
            val apiClient = jiraServiceImpl.getApiClient()

            if (apiClient == null) {
                withContext(Dispatchers.EDT) {
                    showMessage("Jira not configured.")
                }
                return@launch
            }

            val result = apiClient.getDevStatusPullRequests(issueId)

            withContext(Dispatchers.EDT) {
                when (result) {
                    is ApiResult.Success -> {
                        val pullRequests = result.data
                        if (pullRequests.isEmpty()) {
                            showMessage("No pull requests linked.")
                        } else {
                            renderPullRequests(pullRequests)
                        }
                    }
                    is ApiResult.Error -> {
                        log.warn("[Jira:UI] Failed to load dev status for issue $issueId: ${result.message}")
                        showMessage("Could not load pull requests.")
                    }
                }
            }
        }
    }

    private fun showMessage(text: String) {
        removeAll()
        add(JBLabel(text).apply {
            foreground = StatusColors.SECONDARY_TEXT
            border = JBUI.Borders.empty(8)
        }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun renderPullRequests(pullRequests: List<DevStatusPullRequest>) {
        removeAll()

        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        for (pr in pullRequests) {
            container.add(createPrRow(pr))
        }

        add(container, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createPrRow(pr: DevStatusPullRequest): JPanel {
        val rowPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            border = JBUI.Borders.empty(2, 8)
        }

        // Status badge
        val statusText = pr.status.uppercase()
        val statusColor = when (statusText) {
            "OPEN" -> StatusColors.SUCCESS
            "MERGED" -> StatusColors.MERGED
            "DECLINED" -> StatusColors.ERROR
            else -> StatusColors.SECONDARY_TEXT
        }
        rowPanel.add(JBLabel("[$statusText]").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            foreground = statusColor
        })

        // PR name (clickable)
        rowPanel.add(JBLabel(pr.name).apply {
            font = font.deriveFont(JBUI.scale(11).toFloat())
            foreground = StatusColors.LINK
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            if (pr.url.isNotBlank()) {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) {
                        BrowserUtil.browse(pr.url)
                    }
                })
            }
        })

        return rowPanel
    }

    fun dispose() {
        scope.cancel()
    }
}

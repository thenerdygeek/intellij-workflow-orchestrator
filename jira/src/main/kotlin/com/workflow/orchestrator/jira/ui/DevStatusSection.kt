package com.workflow.orchestrator.jira.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
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
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * Lazy-loaded dev status section for the ticket detail panel.
 *
 * Shows linked pull requests with colored badge backgrounds (OPEN/MERGED/DECLINED)
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

        val container = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
        }

        for (pr in pullRequests) {
            container.add(createPrCard(pr))
        }

        add(container, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun createPrCard(pr: DevStatusPullRequest): JPanel {
        val statusText = pr.status.uppercase()
        val (badgeBg, badgeFg, cardBorderColor) = when (statusText) {
            "OPEN" -> Triple(OPEN_BADGE_BG, OPEN_BADGE_FG, OPEN_BORDER)
            "MERGED" -> Triple(MERGED_BADGE_BG, MERGED_BADGE_FG, MERGED_BORDER)
            "DECLINED" -> Triple(DECLINED_BADGE_BG, DECLINED_BADGE_FG, DECLINED_BORDER)
            else -> Triple(DEFAULT_BADGE_BG, StatusColors.SECONDARY_TEXT, StatusColors.BORDER)
        }

        return object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8, 4, 8)
                cursor = if (pr.url.isNotBlank()) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                if (pr.url.isNotBlank()) {
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            BrowserUtil.browse(pr.url)
                        }
                    })
                }
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = JBUI.scale(4).toFloat()
                // Card background (tinted)
                g2.color = cardBorderColor
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f)
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r, r))
                // Card border
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, r, r))
                g2.dispose()
            }
        }.apply {
            // Status badge with colored background
            add(object : JPanel() {
                init {
                    isOpaque = false
                    val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat()))
                    preferredSize = Dimension(
                        fm.stringWidth(statusText) + JBUI.scale(8),
                        fm.height + JBUI.scale(4)
                    )
                }

                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    com.workflow.orchestrator.core.ui.RenderingUtils.applyDesktopHints(g2)
                    val r = JBUI.scale(3).toFloat()
                    g2.color = badgeBg
                    g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r, r))
                    g2.color = badgeFg
                    g2.font = font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat())
                    val fm = g2.fontMetrics
                    g2.drawString(statusText,
                        (width - fm.stringWidth(statusText)) / 2,
                        (height + fm.ascent - fm.descent) / 2)
                    g2.dispose()
                }
            })

            // PR name (monospace-style)
            add(JBLabel(pr.name).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
                foreground = when (statusText) {
                    "OPEN" -> OPEN_TEXT
                    "MERGED" -> MERGED_TEXT
                    "DECLINED" -> StatusColors.ERROR
                    else -> StatusColors.SECONDARY_TEXT
                }
            })
        }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        // OPEN: green tones
        private val OPEN_BADGE_BG = JBColor(0xDCFFDD, 0x1A3D1A)
        private val OPEN_BADGE_FG = JBColor(0x1B7F37, 0x3FB950)
        private val OPEN_BORDER = JBColor(0xA5D6A7, 0x2E7D32)
        private val OPEN_TEXT = JBColor(0x1B7F37, 0xA5D6A7)

        // MERGED: purple tones
        private val MERGED_BADGE_BG = JBColor(0xE8D5F5, 0x3D1F6B)
        private val MERGED_BADGE_FG = JBColor(0x6F42C1, 0xBC8CFF)
        private val MERGED_BORDER = JBColor(0xB39DDB, 0x6F42C1)
        private val MERGED_TEXT = JBColor(0x6F42C1, 0xCE93D8)

        // DECLINED: red tones
        private val DECLINED_BADGE_BG = JBColor(0xFFE0E0, 0x3D1A1A)
        private val DECLINED_BADGE_FG = JBColor(0xCF222E, 0xF85149)
        private val DECLINED_BORDER = JBColor(0xEF9A9A, 0xCF222E)

        // Default
        private val DEFAULT_BADGE_BG = JBColor(0xE8EAED, 0x3D4043)
    }
}

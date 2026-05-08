package com.workflow.orchestrator.jira.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.jira.DevStatusBranchData
import com.workflow.orchestrator.core.model.jira.DevStatusBuildData
import com.workflow.orchestrator.core.model.jira.DevStatusBundle
import com.workflow.orchestrator.core.model.jira.DevStatusCommitData
import com.workflow.orchestrator.core.model.jira.DevStatusDeploymentData
import com.workflow.orchestrator.core.model.jira.DevStatusPrData
import com.workflow.orchestrator.core.model.jira.DevStatusReviewData
import com.workflow.orchestrator.core.ui.StatusColors
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
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Full Jira Development Panel — shows branches, PRs, commits, builds, deployments, and reviews
 * with a chip strip summary and per-type collapsible sub-sections.
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
            add(JBLabel(AnimatedIcon.Default()).apply { border = JBUI.Borders.empty(8) })
            add(JBLabel("Loading dev status...").apply { foreground = StatusColors.SECONDARY_TEXT })
        }
        add(loadingPanel, BorderLayout.CENTER)
        revalidate()
        repaint()

        scope.launch {
            val service = JiraServiceImpl.getInstance(project)
            val result = service.getFullDevStatus(issueId)
            withContext(Dispatchers.EDT) {
                if (result.isError) {
                    showMessage("Could not load dev status.")
                } else {
                    renderBundle(result.data!!)
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

    private fun renderBundle(bundle: DevStatusBundle) {
        removeAll()

        if (bundle.isEmpty) {
            val msg = if (bundle.fetchErrors > 0)
                "Could not load dev status (${bundle.fetchErrors} of 6 feeds errored)."
            else
                "No linked development activity."
            showMessage(msg)
            return
        }

        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Partial-error notice
        if (bundle.fetchErrors > 0) {
            val notice = JBLabel("Partial result — ${bundle.fetchErrors} of 6 feeds errored").apply {
                font = font.deriveFont(Font.ITALIC, JBUI.scale(10).toFloat())
                foreground = StatusColors.SECONDARY_TEXT
                border = JBUI.Borders.empty(2, 8)
            }
            notice.alignmentX = Component.LEFT_ALIGNMENT
            outer.add(notice)
        }

        // Chip strip
        val chipStrip = buildChipStrip(bundle)
        if (chipStrip != null) {
            chipStrip.alignmentX = Component.LEFT_ALIGNMENT
            chipStrip.maximumSize = Dimension(Int.MAX_VALUE, chipStrip.preferredSize.height)
            outer.add(chipStrip)
        }

        val sections = mutableListOf<CollapsibleSection>()

        fun addSection(title: String, items: List<JComponent>, autoExpand: Boolean) {
            if (items.isEmpty()) return
            val content = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                border = JBUI.Borders.empty(2, 8)
                items.forEach { row ->
                    row.alignmentX = Component.LEFT_ALIGNMENT
                    row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
                    add(row)
                }
            }
            val section = CollapsibleSection(title, content, autoExpand, items.size)
            section.alignmentX = Component.LEFT_ALIGNMENT
            section.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            sections.add(section)
            outer.add(section)
        }

        val failedBuild = bundle.builds.any { it.state.uppercase() in setOf("FAILED", "FAILURE") }
        val declinedPr = bundle.pullRequests.any { it.status.uppercase() == "DECLINED" }

        addSection("BRANCHES", bundle.branches.map { renderBranchRow(it) }, bundle.builds.isEmpty() && bundle.pullRequests.isEmpty())
        addSection("PULL REQUESTS", bundle.pullRequests.map { renderPrRow(it) }, declinedPr || bundle.builds.isEmpty())
        addSection("COMMITS", bundle.commits.map { renderCommitRow(it) }, false)
        addSection("BUILDS", bundle.builds.map { renderBuildRow(it) }, failedBuild)
        addSection("DEPLOYMENTS", bundle.deployments.map { renderDeploymentRow(it) }, false)
        addSection("REVIEWS", bundle.reviews.map { renderReviewRow(it) }, false)

        add(outer, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // ---- Chip strip ----

    private fun buildChipStrip(bundle: DevStatusBundle): JPanel? {
        val chips = buildList {
            if (bundle.branches.isNotEmpty()) add(bundle.branches.size to "branches")
            if (bundle.pullRequests.isNotEmpty()) add(bundle.pullRequests.size to "PRs")
            if (bundle.commits.isNotEmpty()) add(bundle.commits.size to "commits")
            if (bundle.builds.isNotEmpty()) add(bundle.builds.size to "builds")
            if (bundle.deployments.isNotEmpty()) add(bundle.deployments.size to "deploys")
            if (bundle.reviews.isNotEmpty()) add(bundle.reviews.size to "reviews")
        }
        if (chips.isEmpty()) return null
        return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 2, 8)
            chips.forEach { (count, label) ->
                add(buildChip("$count $label"))
            }
        }
    }

    private fun buildChip(text: String): JComponent {
        return object : JPanel() {
            init {
                isOpaque = false
                val fm = getFontMetrics(font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat()))
                preferredSize = Dimension(fm.stringWidth(text) + JBUI.scale(12), fm.height + JBUI.scale(6))
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = JBUI.scale(10).toFloat()
                g2.color = DEFAULT_BADGE_BG
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r, r))
                g2.color = StatusColors.SECONDARY_TEXT
                g2.font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                val fm = g2.fontMetrics
                g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
                g2.dispose()
            }
        }
    }

    // ---- Row renderers ----

    private fun renderBranchRow(branch: DevStatusBranchData): JComponent =
        buildRow(
            badgeBg = OPEN_BADGE_BG,
            badgeFg = OPEN_BADGE_FG,
            badgeText = "BRANCH",
            cardBorderColor = OPEN_BORDER,
            primaryText = branch.name,
            primaryFg = OPEN_TEXT,
            url = branch.url
        )

    private fun renderPrRow(pr: DevStatusPrData): JComponent {
        val statusText = pr.status.uppercase()
        val (badgeBg, badgeFg, borderColor, textFg) = when (statusText) {
            "OPEN" -> Quad(OPEN_BADGE_BG, OPEN_BADGE_FG, OPEN_BORDER, OPEN_TEXT)
            "MERGED" -> Quad(MERGED_BADGE_BG, MERGED_BADGE_FG, MERGED_BORDER, MERGED_TEXT)
            "DECLINED" -> Quad(DECLINED_BADGE_BG, DECLINED_BADGE_FG, DECLINED_BORDER, StatusColors.ERROR)
            else -> Quad(DEFAULT_BADGE_BG, StatusColors.SECONDARY_TEXT, StatusColors.BORDER, StatusColors.SECONDARY_TEXT)
        }
        return buildRow(badgeBg, badgeFg, statusText, borderColor, pr.name, textFg, pr.url)
    }

    private fun renderCommitRow(commit: DevStatusCommitData): JComponent {
        val label = if (commit.displayId.isNotBlank()) commit.displayId else commit.message.take(12)
        val meta = commit.message.take(60).let { if (commit.message.length > 60) "$it…" else it }
        return buildRow(
            badgeBg = DEFAULT_BADGE_BG,
            badgeFg = StatusColors.SECONDARY_TEXT,
            badgeText = "COMMIT",
            cardBorderColor = StatusColors.BORDER,
            primaryText = label,
            primaryFg = StatusColors.LINK,
            url = commit.url,
            metaText = meta
        )
    }

    private fun renderBuildRow(build: DevStatusBuildData): JComponent {
        val stateText = build.state.uppercase()
        val (badgeBg, badgeFg, borderColor) = when (stateText) {
            "SUCCESSFUL", "SUCCESS" -> Triple(OPEN_BADGE_BG, OPEN_BADGE_FG, OPEN_BORDER)
            "FAILED", "FAILURE" -> Triple(DECLINED_BADGE_BG, DECLINED_BADGE_FG, DECLINED_BORDER)
            "IN_PROGRESS", "INPROGRESS", "BUILDING" -> Triple(BLUE_BADGE_BG, BLUE_BADGE_FG, BLUE_BADGE_BORDER)
            else -> Triple(DEFAULT_BADGE_BG, StatusColors.SECONDARY_TEXT, StatusColors.BORDER)
        }
        return buildRow(badgeBg, badgeFg, stateText, borderColor, build.name, StatusColors.SECONDARY_TEXT, build.url)
    }

    private fun renderDeploymentRow(deployment: DevStatusDeploymentData): JComponent {
        val envType = deployment.environmentType?.uppercase() ?: ""
        val (badgeBg, badgeFg, borderColor) = when {
            envType == "PRODUCTION" || envType == "PROD" -> Triple(MERGED_BADGE_BG, MERGED_BADGE_FG, MERGED_BORDER)
            envType == "STAGING" || envType == "STAGE" -> Triple(AMBER_BADGE_BG, AMBER_BADGE_FG, AMBER_BADGE_BORDER)
            else -> Triple(DEFAULT_BADGE_BG, StatusColors.SECONDARY_TEXT, StatusColors.BORDER)
        }
        val envLabel = deployment.environmentName?.let { "→ $it" } ?: ""
        return buildRow(badgeBg, badgeFg, deployment.state.uppercase().take(12), borderColor, deployment.displayName, StatusColors.SECONDARY_TEXT, deployment.url, envLabel)
    }

    private fun renderReviewRow(review: DevStatusReviewData): JComponent {
        val stateText = review.state.uppercase()
        val (badgeBg, badgeFg, borderColor) = when (stateText) {
            "APPROVED" -> Triple(OPEN_BADGE_BG, OPEN_BADGE_FG, OPEN_BORDER)
            "NEEDS_WORK", "CHANGES_REQUESTED" -> Triple(DECLINED_BADGE_BG, DECLINED_BADGE_FG, DECLINED_BORDER)
            else -> Triple(BLUE_BADGE_BG, BLUE_BADGE_FG, BLUE_BADGE_BORDER)
        }
        return buildRow(badgeBg, badgeFg, stateText, borderColor, review.name, StatusColors.SECONDARY_TEXT, review.url)
    }

    // ---- Core row builder ----

    private fun buildRow(
        badgeBg: JBColor,
        badgeFg: JBColor,
        badgeText: String,
        cardBorderColor: JBColor,
        primaryText: String,
        primaryFg: Color,
        url: String,
        metaText: String? = null
    ): JPanel {
        return object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(3, 8, 3, 8)
                if (url.isNotBlank()) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) { BrowserUtil.browse(url) }
                    })
                }
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = JBUI.scale(4).toFloat()
                g2.color = cardBorderColor
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f)
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r, r))
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, r, r))
                g2.dispose()
            }
        }.apply {
            // Badge
            add(object : JPanel() {
                init {
                    isOpaque = false
                    val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat()))
                    preferredSize = Dimension(fm.stringWidth(badgeText) + JBUI.scale(8), fm.height + JBUI.scale(4))
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
                    g2.drawString(badgeText, (width - fm.stringWidth(badgeText)) / 2, (height + fm.ascent - fm.descent) / 2)
                    g2.dispose()
                }
            })

            // Primary label
            add(JBLabel(primaryText).apply {
                font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11))
                foreground = primaryFg
            })

            // Optional meta text
            if (!metaText.isNullOrBlank()) {
                add(JBLabel(metaText).apply {
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    foreground = StatusColors.SECONDARY_TEXT
                })
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // Helper to destructure 4 values without Pair nesting
    private data class Quad<A>(val a: A, val b: A, val c: A, val d: A)

    companion object {
        // OPEN / green
        private val OPEN_BADGE_BG = JBColor(0xDCFFDD, 0x1A3D1A)
        private val OPEN_BADGE_FG = JBColor(0x1B7F37, 0x3FB950)
        private val OPEN_BORDER = JBColor(0xA5D6A7, 0x2E7D32)
        private val OPEN_TEXT = JBColor(0x1B7F37, 0xA5D6A7)

        // MERGED / purple
        private val MERGED_BADGE_BG = JBColor(0xE8D5F5, 0x3D1F6B)
        private val MERGED_BADGE_FG = JBColor(0x6F42C1, 0xBC8CFF)
        private val MERGED_BORDER = JBColor(0xB39DDB, 0x6F42C1)
        private val MERGED_TEXT = JBColor(0x6F42C1, 0xCE93D8)

        // DECLINED / red
        private val DECLINED_BADGE_BG = JBColor(0xFFE0E0, 0x3D1A1A)
        private val DECLINED_BADGE_FG = JBColor(0xCF222E, 0xF85149)
        private val DECLINED_BORDER = JBColor(0xEF9A9A, 0xCF222E)

        // Default / gray
        private val DEFAULT_BADGE_BG = JBColor(0xE8EAED, 0x3D4043)

        // Blue / in-progress
        private val BLUE_BADGE_BG = JBColor(0xDDEEFF, 0x1A2A3D)
        private val BLUE_BADGE_FG = JBColor(0x0969DA, 0x58A6FF)
        private val BLUE_BADGE_BORDER = JBColor(0xADD8E6, 0x1F4068)

        // Amber / staging
        private val AMBER_BADGE_BG = JBColor(0xFFF4D9, 0x3D2F1A)
        private val AMBER_BADGE_FG = JBColor(0xB8860B, 0xE3B341)
        private val AMBER_BADGE_BORDER = JBColor(0xCC8800, 0xE3B341)
    }
}

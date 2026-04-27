package com.workflow.orchestrator.bamboo.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.model.workflow.InteractionMode
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.ui.StatusColors
import com.workflow.orchestrator.core.util.HtmlEscape
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * PR bar for the Build tab — passive mirror of [WorkflowContextService.state].
 *
 * Two states, both driven by `state.value.focusPr` + `state.value.interactionMode`:
 *  1. Live + focusPr != null   → green strip with "PR-#id  fromBranch → toBranch" + Open-in-browser
 *  2. otherwise                → blue strip with "No PR for this branch — pick one in the PR tab →"
 *                                + clickable affordance that activates the Workflow tool window
 *                                and selects the PR tab.
 *
 * This bar no longer fetches PRs from Bitbucket, no longer polls, and no longer hosts a
 * "+ Create PR" button. It is purely a render of whatever the PR tab focused (via the
 * canonical [WorkflowContextService.focusPr]). All branch-match / read-only gating is
 * derived from [WorkflowContext.interactionMode] — no local state machine.
 *
 * Trade-off: PR title is NOT rendered (only `PR-#id` + branches). [PrRef] doesn't carry
 * the title, and re-introducing a Bitbucket fetch here would defeat the redesign.  If we
 * later need the title, the right move is to extend [PrRef] to carry it (broadcast by the
 * PR tab via `WorkflowContextService.focusPr(...)`) — not to add a fetch path here.
 */
class PrBar(
    private val project: Project,
    private val scope: CoroutineScope,
) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(PrBar::class.java)
    private val contextService = WorkflowContextService.getInstance(project)
    private val contentPanel = JPanel(BorderLayout())

    // Track currently rendered PR so the "Open in browser" link knows what to open.
    @Volatile
    private var renderedPr: PrRef? = null

    // --- Empty-state panel (shown when interactionMode == ReadOnly OR focusPr == null) ---
    private val emptyPanel = JPanel(BorderLayout())
    private val emptyMessageLabel = JBLabel(EMPTY_MESSAGE).apply {
        foreground = StatusColors.LINK
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    // --- Single-PR rendered state ---
    private val singlePrPanel = JPanel(BorderLayout())
    private val prInfoLabel = JBLabel("")
    private val openInBrowserLink = JBLabel("Open in browser ↗").apply {
        foreground = StatusColors.LINK
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }

    companion object {
        private val BLUE_BG = StatusColors.INFO_BG
        private val GREEN_BG = StatusColors.SUCCESS_BG
        private val BLUE_BORDER = StatusColors.LINK
        private val GREEN_BORDER = StatusColors.SUCCESS
        private const val EMPTY_MESSAGE = "No PR for this branch — pick one in the PR tab →"
    }

    init {
        buildEmptyPanel()
        buildSinglePrPanel()
        add(contentPanel, BorderLayout.CENTER)
        showPanel(emptyPanel)

        // Subscribe to the canonical state. EVERY render decision flows from this single
        // collector — no event subscriptions, no Bitbucket fetches, no internal state.
        scope.launch {
            contextService.state
                .distinctUntilChanged { a, b ->
                    a.focusPr == b.focusPr && a.interactionMode == b.interactionMode
                }
                .collect { ctx ->
                    invokeLater { renderFromContext(ctx) }
                }
        }
    }

    private fun buildEmptyPanel() {
        emptyPanel.background = BLUE_BG
        emptyPanel.border = JBUI.Borders.customLine(BLUE_BORDER, 0, 0, 1, 0)
        emptyPanel.preferredSize = java.awt.Dimension(0, JBUI.scale(36))
        emptyPanel.maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(JBLabel(AllIcons.Vcs.Branch))
            add(emptyMessageLabel)
        }
        emptyPanel.add(left, BorderLayout.CENTER)

        emptyMessageLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                switchToPrTab()
            }
        })
    }

    private fun buildSinglePrPanel() {
        singlePrPanel.background = GREEN_BG
        singlePrPanel.border = JBUI.Borders.customLine(GREEN_BORDER, 0, 0, 1, 0)
        singlePrPanel.preferredSize = java.awt.Dimension(0, JBUI.scale(36))
        singlePrPanel.maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(36))

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(JBLabel("✓").apply { foreground = StatusColors.SUCCESS })
            add(prInfoLabel)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), JBUI.scale(4))).apply {
            isOpaque = false
            add(openInBrowserLink)
        }
        singlePrPanel.add(left, BorderLayout.CENTER)
        singlePrPanel.add(right, BorderLayout.EAST)

        openInBrowserLink.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                openCurrentPrInBrowser()
            }
        })
    }

    private fun showPanel(panel: JPanel) {
        contentPanel.removeAll()
        contentPanel.add(panel, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()
        this.revalidate()
        this.repaint()
    }

    /**
     * Render decision from a [WorkflowContext] snapshot. Single source of truth for what
     * the bar shows — no other path mutates UI state.
     */
    private fun renderFromContext(ctx: WorkflowContext) {
        val focus = ctx.focusPr
        if (focus != null && ctx.interactionMode == InteractionMode.Live) {
            renderedPr = focus
            // PR title not rendered — see class kdoc trade-off.
            prInfoLabel.text = "<html><b>PR #${focus.prId}</b> &nbsp; " +
                "${HtmlEscape.escapeHtml(focus.fromBranch)} &rarr; " +
                "${HtmlEscape.escapeHtml(focus.toBranch)}</html>"
            showPanel(singlePrPanel)
        } else {
            renderedPr = null
            showPanel(emptyPanel)
        }
    }

    private fun switchToPrTab() {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow")
        if (toolWindow == null) {
            log.warn("[Build:PrBar] Workflow tool window not found")
            return
        }
        toolWindow.activate {
            val cm = toolWindow.contentManager
            cm.contents.firstOrNull { it.displayName == "PR" }
                ?.let { cm.setSelectedContent(it) }
        }
    }

    /**
     * Open the focused PR in the browser using the configured Bitbucket base URL.
     * Built from PrRef + connection settings — no Bitbucket REST fetch.
     */
    private fun openCurrentPrInBrowser() {
        val pr = renderedPr ?: return
        val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
        val bitbucketUrl = settings.connections.bitbucketUrl.orEmpty().trimEnd('/')
        if (bitbucketUrl.isBlank()) return
        val repoConfig = settings.getRepos().firstOrNull { it.displayLabel == pr.repoName || it.name == pr.repoName }
        val projectKey = repoConfig?.bitbucketProjectKey?.takeIf { it.isNotBlank() }
            ?: settings.state.bitbucketProjectKey.orEmpty()
        val repoSlug = repoConfig?.bitbucketRepoSlug?.takeIf { it.isNotBlank() }
            ?: settings.state.bitbucketRepoSlug.orEmpty()
        if (projectKey.isBlank() || repoSlug.isBlank()) {
            log.warn("[Build:PrBar] Cannot open PR — Bitbucket project/repo not configured for '${pr.repoName}'")
            return
        }
        val url = "$bitbucketUrl/projects/$projectKey/repos/$repoSlug/pull-requests/${pr.prId}"
        com.intellij.ide.BrowserUtil.browse(url)
    }
}

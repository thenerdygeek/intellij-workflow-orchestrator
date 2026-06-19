package com.workflow.orchestrator.jira.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.workflow.orchestrator.core.ui.StatusColors
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.util.DefaultBranchResolver
import com.workflow.orchestrator.core.workflow.TicketBranchLocator
import com.workflow.orchestrator.core.workflow.ui.BranchSwitchAction
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.*
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * "Currently Working On" section at the top of the Sprint tab left panel.
 * Shows the active ticket with branch name, status, and quick stats.
 */
class CurrentWorkSection(
    private val project: Project,
    private val onTicketClicked: ((String) -> Unit)? = null,
    locatorOverride: TicketBranchLocator? = null,
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val settings get() = PluginSettings.getInstance(project)
    private val locator: TicketBranchLocator =
        locatorOverride ?: TicketBranchLocator.getInstance(project)

    private val ticketKeyLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        foreground = StatusColors.LINK
    }
    private val summaryLabel = JBLabel("").apply {
        font = font.deriveFont(JBUI.scale(11).toFloat())
    }
    private val editTargetLabel = JBLabel(AllIcons.Actions.Edit).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Change target branch"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val ACTIVE_BG = JBColor(0xE8F5E9, 0x2A3B2A)
        private val ACTIVE_BORDER = JBColor(0xA5D6A7, 0x3B5D3E)
        private val CHIP_BG = JBColor(0xE0E0E0, 0x2D3035)
        private val EMPTY_BG = StatusColors.CARD_BG
    }

    init {
        buildEmptyState()

        // P2-20: register the editTargetLabel mouse listener ONCE here in init, not
        // per buildActiveState call. buildActiveState previously removed all listeners
        // and re-added one on every refresh — a no-op correctness-wise but wasteful.
        editTargetLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                showBranchPicker()
            }
        })

        scope.launch {
            project.getService(EventBus::class.java).events.collect { event ->
                when (event) {
                    is WorkflowEvent.TicketChanged,
                    is WorkflowEvent.BranchChanged -> invokeLater { refresh() }
                    else -> Unit
                }
            }
        }
    }

    fun refresh() {
        val ticketId = settings.state.activeTicketId.orEmpty()
        val ticketSummary = settings.state.activeTicketSummary.orEmpty()

        if (ticketId.isBlank()) {
            buildEmptyState()
        } else {
            buildActiveState(ticketId, ticketSummary)
        }
        revalidate()
        repaint()
    }

    private fun buildActiveState(ticketId: String, summary: String) {
        removeAll()

        // Card with green-tinted background and border
        val cardPanel = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = JBUI.scale(4).toFloat()
                g2.color = ACTIVE_BG
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r, r))
                g2.color = ACTIVE_BORDER
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, r, r))
                g2.dispose()
            }
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
        }

        // Ticket key + edit icon row
        val keyRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(18))
        }
        ticketKeyLabel.text = ticketId
        keyRow.add(ticketKeyLabel)
        keyRow.add(editTargetLabel)
        inner.add(keyRow)

        // Summary
        summaryLabel.text = summary
        summaryLabel.alignmentX = Component.LEFT_ALIGNMENT
        inner.add(summaryLabel)
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))

        // editTargetLabel listener is registered once in init — not re-added here.

        // Branch rows: rendered by CurrentWorkChipRenderer based on the
        // LocateResult returned by TicketBranchLocator. The renderer is pure
        // (stateless) so it can be unit-tested without BasePlatformTestCase.
        val rowsHost = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        inner.add(rowsHost)

        scope.launch {
            val result = locator.locate(ticketId)
            invokeLater {
                CurrentWorkChipRenderer.render(
                    host = rowsHost,
                    result = result,
                    ticketId = ticketId,
                    onSettingsClick = {
                        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, "Workflow Orchestrator")
                    },
                    onSwitchClick = { row ->
                        val rootPath = row.repo.localVcsRootPath ?: return@render
                        scope.launch {
                            BranchSwitchAction.trySwitchTo(project, rootPath, row.branchDisplayId)
                        }
                    },
                )
                rowsHost.revalidate()
                rowsHost.repaint()
            }
        }

        cardPanel.add(inner, BorderLayout.CENTER)
        add(cardPanel, BorderLayout.CENTER)

        // Click to select ticket in the list
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        mouseListeners.forEach { removeMouseListener(it) }
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onTicketClicked?.invoke(ticketId)
            }
        })
    }

    private fun showBranchPicker() {
        // P2-20: move the read off EDT. Previously called runReadAction { }
        // synchronously on the EDT (flagged in phase4-closeout.md §"Two intentional
        // debt items" as deferred — now resolved via scope.launch + a blocking
        // runReadAction on the IO dispatcher, which is safe off-EDT).
        // The popup is created and shown back on the EDT after the read completes.
        scope.launch {
            val repo = runReadAction {
                GitRepositoryManager.getInstance(project).repositories.firstOrNull()
            } ?: return@launch
            val branches = repo.branches.remoteBranches
                .map { it.nameForRemoteOperations }
                .filter { it != "HEAD" }
                .sorted()

            // Back to EDT for Swing popup construction — use invokeLater (already imported)
            // so no additional EDT import is needed (baseline pinned for this file).
            invokeLater {
                // The click→popup path is now async: the ticket may have cleared
                // mid-flight (buildEmptyState removes editTargetLabel from the
                // hierarchy), and showUnderneathOf on a non-showing component
                // throws. Bail out silently in that case (W6-D3 review M4).
                if (!editTargetLabel.isShowing) return@invokeLater

                val list = com.intellij.ui.components.JBList(branches)
                list.selectionMode = ListSelectionModel.SINGLE_SELECTION

                val popup = JBPopupFactory.getInstance()
                    .createListPopupBuilder(list)
                    .setTitle("Select Target Branch")
                    .setFilterAlwaysVisible(true)
                    .setItemChoosenCallback {
                        val selected = list.selectedValue as? String ?: return@setItemChoosenCallback
                        val resolver = DefaultBranchResolver.getInstance(project)
                        val repoPath = repo.root.path
                        resolver.setOverride(repoPath, repo.currentBranchName ?: "", selected)
                        // Invalidate locator cache, then refresh on EDT so the re-render
                        // picks up the new target branch (sequenced to avoid a race
                        // between invalidate and locate).
                        val ticketId = settings.state.activeTicketId.orEmpty()
                        scope.launch {
                            locator.invalidate(ticketId)
                            com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeLater { refresh() }
                        }
                    }
                    .createPopup()

                popup.showUnderneathOf(editTargetLabel)
            }
        }
    }

    private fun buildEmptyState() {
        removeAll()
        isOpaque = false

        val cardPanel = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(8, 10)
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = JBUI.scale(6).toFloat()
                g2.color = EMPTY_BG
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), r, r))
                // Dashed border
                g2.color = StatusColors.BORDER
                g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, r, r))
                g2.dispose()
            }
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
        }

        inner.add(JBLabel("CURRENTLY WORKING ON").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(Font.BOLD, JBUI.scale(9).toFloat())
            alignmentX = Component.LEFT_ALIGNMENT
        })
        inner.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        inner.add(JBLabel("No active ticket").apply {
            foreground = StatusColors.SECONDARY_TEXT
            font = font.deriveFont(JBUI.scale(11).toFloat())
            alignmentX = Component.LEFT_ALIGNMENT
        })
        inner.add(JBLabel("Select a ticket and click Start Work").apply {
            foreground = StatusColors.LINK
            font = font.deriveFont(JBUI.scale(10).toFloat())
            alignmentX = Component.LEFT_ALIGNMENT
        })

        cardPanel.add(inner, BorderLayout.CENTER)
        add(cardPanel, BorderLayout.CENTER)
    }

    override fun dispose() {
        scope.cancel()
    }
}

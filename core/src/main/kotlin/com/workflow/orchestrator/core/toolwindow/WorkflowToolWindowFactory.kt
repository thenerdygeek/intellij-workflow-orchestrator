package com.workflow.orchestrator.core.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.util.ui.JBUI
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.StatusColors
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class WorkflowToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        /** A default tab with no provider stays visible; otherwise honor the provider's isAvailable. */
        @JvmStatic
        fun isTabAvailable(provider: WorkflowTabProvider?, project: Project): Boolean =
            provider?.isAvailable(project) ?: true
    }

    private val log = Logger.getInstance(WorkflowToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Per-ToolWindow lazy-tab state. Scoped here (NOT a factory instance field) so two
        // open projects sharing the factory instance can't clobber each other's tracking.
        val materializedTabs = mutableSetOf<String>()
        val rebuildInProgress = AtomicBoolean(false)
        val rebuildTabs: () -> Unit = {
            // While removeAllContents(true) runs, the content manager shifts selection onto
            // soon-to-be-removed contents and fires selectionChanged(add). Without this guard
            // the lazy-materialization listener below would materialize a doomed placeholder
            // (for the Agent tab that means a transient Chromium spawn per rebuild).
            rebuildInProgress.set(true)
            try {
                buildTabs(project, toolWindow, materializedTabs)
            } finally {
                rebuildInProgress.set(false)
            }
        }

        // Rebuild tabs when a tab's availability is resolved asynchronously
        // (e.g. the Jira-Agile capability probe completes -> hide/show Sprint).
        // Registered BEFORE the first rebuildTabs() so the replay=0 EventBus can't
        // drop a probe result emitted during that first build.
        val availabilityScope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        availabilityScope.launch {
            project.getService(com.workflow.orchestrator.core.events.EventBus::class.java)
                .events
                .collect { event ->
                    if (event is com.workflow.orchestrator.core.events.WorkflowEvent.TabAvailabilityChanged) {
                        val cm = toolWindow.contentManager
                        val selectedTab = cm.selectedContent?.displayName
                        rebuildTabs()
                        // Restore the previous selection if it still exists; otherwise select
                        // the first surviving content so we never sit on a removed/blank tab.
                        val restored = selectedTab?.let { prev -> cm.contents.firstOrNull { it.displayName == prev } }
                        if (restored != null) {
                            cm.setSelectedContent(restored)
                        } else {
                            cm.contents.firstOrNull()?.let { cm.setSelectedContent(it) }
                        }
                    }
                }
        }
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable) { availabilityScope.cancel() }

        rebuildTabs() // first build — collector above is already live

        // Listen for tab selection to materialize lazy tabs on demand.
        // Registered exactly ONCE per ToolWindow (P0-6): buildTabs() is re-run by
        // "Refresh All Tabs" and the settings-change toolWindowShown path, and registering
        // inside buildTabs stacked one listener per rebuild.
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (rebuildInProgress.get()) return
                val content = event.content
                val tabTitle = content.displayName ?: return
                if (event.operation != ContentManagerEvent.ContentOperation.add) return
                if (tabTitle in materializedTabs) return
                val realPanel = materializeByTitle(project, tabTitle, materializedTabs) ?: return
                content.component = realPanel
                // Now that the placeholder is replaced by the real panel, wire the
                // dispose cascade. LazyTabPlaceholder isn't Disposable, so no prior
                // disposer was registered at buildTabs time -- this is a fresh set,
                // not a replacement. On the next rebuild, removeAllContents(true)
                // disposes this content and the disposer cascade tears the panel down.
                if (realPanel is Disposable) {
                    content.setDisposer(realPanel)
                }
                log.info("[Workflow:UI] Lazy-loaded tab: $tabTitle")
            }
        })

        setupTitleActions(project, toolWindow)
        setupGearActions(project, toolWindow, rebuildTabs)
        setupActiveTicketBar(project, toolWindow)

        // Rebuild tabs when the tool window is shown, so settings changes take effect
        project.messageBus.connect(toolWindow.disposable)
            .subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    private var lastSettingsSnapshot = settingsSnapshot(project)

                    override fun toolWindowShown(tw: ToolWindow) {
                        if (tw.id != toolWindow.id) return
                        val current = settingsSnapshot(project)
                        if (current != lastSettingsSnapshot) {
                            lastSettingsSnapshot = current
                            val selectedTab = toolWindow.contentManager.selectedContent?.displayName
                            rebuildTabs()
                            // Restore previously selected tab
                            if (selectedTab != null) {
                                toolWindow.contentManager.contents
                                    .firstOrNull { it.displayName == selectedTab }
                                    ?.let { toolWindow.contentManager.setSelectedContent(it) }
                            }
                        }
                    }
                }
            )
    }

    // ---------------------------------------------------------------
    // Active ticket header bar (visible across all tabs)
    // ---------------------------------------------------------------

    private fun setupActiveTicketBar(project: Project, toolWindow: ToolWindow) {
        val ticketLabel = JBLabel().apply {
            font = JBUI.Fonts.label().deriveFont(Font.BOLD)
        }
        val summaryLabel = JBLabel().apply {
            foreground = StatusColors.SECONDARY_TEXT
        }

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.Nodes.Tag))
            add(ticketLabel)
            add(summaryLabel)
        }

        val bar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 8)
            background = StatusColors.INFO_BG
            isVisible = false
            add(leftPanel, BorderLayout.CENTER)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    val cm = toolWindow.contentManager
                    val sprintTab = cm.contents.firstOrNull { it.displayName == "Sprint" }
                    if (sprintTab != null) cm.setSelectedContent(sprintTab)
                }
            })
        }
        toolWindow.component.add(bar, BorderLayout.NORTH)

        val service = com.workflow.orchestrator.core.workflow.WorkflowContextService.getInstance(project)
        // The factory is not @Service; CoroutineScope() allocation is permitted here
        // (Phase 4 convention applies to @Service classes only — see core/CLAUDE.md
        // "Service & threading conventions").
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        scope.launch {
            service.activeTicketFlow.collect { ticket ->
                if (ticket != null) {
                    ticketLabel.text = ticket.key
                    summaryLabel.text = ticket.summary
                    bar.isVisible = true
                } else {
                    bar.isVisible = false
                }
                bar.parent?.revalidate()
                bar.parent?.repaint()
            }
        }
        com.intellij.openapi.util.Disposer.register(toolWindow.disposable) { scope.cancel() }
    }

    // ---------------------------------------------------------------
    // Title bar actions (small icon buttons at top of tool window)
    // ---------------------------------------------------------------

    private fun setupTitleActions(project: Project, toolWindow: ToolWindow) {
        toolWindow.setTitleActions(listOf(
            // Refresh current tab
            object : DumbAwareAction("Refresh", "Reload data for the current tab", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    val content = toolWindow.contentManager.selectedContent
                    val selectedTab = content?.displayName
                    log.info("[Workflow:UI] Refresh requested for tab: $selectedTab")
                }
            },

            // Open active ticket in Jira
            object : DumbAwareAction("Open in Jira", "Open active ticket in browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val ticketId = settings.state.activeTicketId
                    val jiraUrl = settings.connections.jiraUrl
                    if (!ticketId.isNullOrBlank() && !jiraUrl.isNullOrBlank()) {
                        BrowserUtil.browse("${jiraUrl.trimEnd('/')}/browse/$ticketId")
                    }
                }

                override fun update(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val hasTicket = !settings.state.activeTicketId.isNullOrBlank()
                    val hasUrl = !settings.connections.jiraUrl.isNullOrBlank()
                    e.presentation.isEnabled = hasTicket && hasUrl
                    if (hasTicket) {
                        e.presentation.text = "Open ${settings.state.activeTicketId} in Jira"
                    }
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            },

            // Open settings
            object : DumbAwareAction("Settings", "Open Workflow Orchestrator settings", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Workflow Orchestrator")
                }
            },

            // Quick navigate between tabs
            object : DumbAwareAction("Next Tab", "Switch to next tab", AllIcons.Actions.Forward) {
                override fun actionPerformed(e: AnActionEvent) {
                    val cm = toolWindow.contentManager
                    val currentIndex = cm.contents.indexOf(cm.selectedContent)
                    val nextIndex = (currentIndex + 1) % cm.contentCount
                    cm.setSelectedContent(cm.contents[nextIndex])
                }
            }
        ))
    }

    // ---------------------------------------------------------------
    // Gear menu actions (wrench dropdown at top-right)
    // ---------------------------------------------------------------

    private fun setupGearActions(project: Project, toolWindow: ToolWindow, rebuildTabs: () -> Unit) {
        val gearGroup = DefaultActionGroup().apply {
            // Settings shortcut
            add(object : DumbAwareAction("Settings", "Configure Workflow Orchestrator", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Workflow Orchestrator")
                }
            })

            addSeparator()

            // Quick toggle modules
            add(object : DumbAwareAction("Refresh All Tabs", "Rebuild all tabs with latest settings", AllIcons.Actions.ForceRefresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    val selectedTab = toolWindow.contentManager.selectedContent?.displayName
                    rebuildTabs()
                    if (selectedTab != null) {
                        toolWindow.contentManager.contents
                            .firstOrNull { it.displayName == selectedTab }
                            ?.let { toolWindow.contentManager.setSelectedContent(it) }
                    }
                }
            })

            // Clear active ticket
            add(object : DumbAwareAction("Clear Active Ticket", "Remove the current active ticket", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    settings.state.activeTicketId = ""
                    settings.state.activeTicketSummary = ""
                    log.info("[Workflow:UI] Active ticket cleared via gear menu")
                }

                override fun update(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    e.presentation.isEnabled = !settings.state.activeTicketId.isNullOrBlank()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            addSeparator()

            // Open Jira board in browser
            add(object : DumbAwareAction("Open Jira Board", "Open Jira board in browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val jiraUrl = settings.connections.jiraUrl
                    if (!jiraUrl.isNullOrBlank()) {
                        BrowserUtil.browse("${jiraUrl.trimEnd('/')}/secure/RapidBoard.jspa")
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !PluginSettings.getInstance(project).connections.jiraUrl.isNullOrBlank()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            // Open SonarQube in browser
            add(object : DumbAwareAction("Open SonarQube", "Open SonarQube dashboard in browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val sonarUrl = settings.connections.sonarUrl
                    if (!sonarUrl.isNullOrBlank()) {
                        BrowserUtil.browse(sonarUrl.trimEnd('/'))
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !PluginSettings.getInstance(project).connections.sonarUrl.isNullOrBlank()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            // Open Bamboo in browser
            add(object : DumbAwareAction("Open Bamboo", "Open Bamboo build dashboard in browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val bambooUrl = settings.connections.bambooUrl
                    if (!bambooUrl.isNullOrBlank()) {
                        BrowserUtil.browse(bambooUrl.trimEnd('/'))
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !PluginSettings.getInstance(project).connections.bambooUrl.isNullOrBlank()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        toolWindow.setAdditionalGearActions(gearGroup)
    }

    // ---------------------------------------------------------------
    // Tab building (C-05 / X-01: lazy tab loading)
    // ---------------------------------------------------------------

    // Default tab descriptors. Immutable -- safe to share across projects/tool windows.
    private val defaultTabs = listOf(
        DefaultTab("Sprint", 0, "No tickets assigned.\nConnect to Jira in Settings to get started."),
        DefaultTab("PR", 1, "No pull requests found.\nConnect to Bitbucket in Settings."),
        DefaultTab("Build", 2, "No builds found.\nPush your changes to trigger a CI build."),
        DefaultTab("Quality", 3, "No quality data available.\nConnect to SonarQube in Settings.")
    )

    private fun currentProviders(): Map<String, WorkflowTabProvider> =
        WorkflowTabProvider.EP_NAME.extensionList
            .sortedBy { it.order }
            .associateBy { it.tabTitle }

    private fun buildTabs(project: Project, toolWindow: ToolWindow, materializedTabs: MutableSet<String>) {
        val contentManager = toolWindow.contentManager
        // removeAllContents(true) disposes each Content, which cascades into any
        // content.setDisposer(panel) wiring -- this is what actually tears down
        // previously materialized panels (incl. the Agent tab's JCEF browser) on rebuild.
        contentManager.removeAllContents(true)
        materializedTabs.clear()

        val providers = currentProviders()

        // Add default tabs (matched with providers by title). Hide any whose provider
        // reports unavailable (e.g. Sprint on non-Software Jira), and eagerly materialize
        // the FIRST VISIBLE tab so the tool window never opens on a perpetual "Loading…"
        // placeholder when the natural first tab (Sprint, order 0) is hidden.
        val visibleDefaults = defaultTabs.filter { isTabAvailable(providers[it.title], project) }
        visibleDefaults.forEachIndexed { index, tab ->
            val panel = if (index == 0) {
                materializeTab(project, tab, providers, materializedTabs)
            } else {
                LazyTabPlaceholder()
            }
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            if (panel is Disposable) {
                content.setDisposer(panel)
            }
            contentManager.addContent(content)
        }

        // Add any extension-provided tabs not in the default list (e.g., Agent tab).
        // These are LAZY like the non-first default tabs (P0-6): the real panel is
        // created only on first selection (see materializeByTitle) -- the Agent tab's
        // Chromium process must not spawn just because the tool window opened on
        // the Sprint tab.
        val defaultTitles = defaultTabs.map { it.title }.toSet()
        providers.filter { it.key !in defaultTitles && it.value.isAvailable(project) }
            .values
            .sortedBy { it.order }
            .forEach { provider ->
                val content = ContentFactory.getInstance()
                    .createContent(LazyTabPlaceholder(), provider.tabTitle, false)
                content.isCloseable = false
                contentManager.addContent(content)
            }
    }

    /**
     * Materializes the real panel for the tab with the given title -- a default tab
     * (with provider override by matching title) or an extension-provided tab.
     * Returns null when the title matches neither (nothing to materialize).
     */
    private fun materializeByTitle(
        project: Project,
        tabTitle: String,
        materializedTabs: MutableSet<String>
    ): JComponent? {
        val providers = currentProviders()
        val defaultTab = defaultTabs.firstOrNull { it.title == tabTitle }
        if (defaultTab != null) return materializeTab(project, defaultTab, providers, materializedTabs)
        val provider = providers[tabTitle] ?: return null
        materializedTabs.add(tabTitle)
        return try {
            provider.createPanel(project)
        } catch (e: Exception) {
            log.warn("[Workflow:UI] Failed to create ${provider.tabTitle} tab: ${e.message}", e)
            EmptyStatePanel(project, "Failed to load ${provider.tabTitle} tab.\n${e.message}")
        }
    }

    /**
     * Creates the real panel for a default tab and marks it as materialized.
     */
    private fun materializeTab(
        project: Project,
        tab: DefaultTab,
        providers: Map<String, WorkflowTabProvider>,
        materializedTabs: MutableSet<String>
    ): JComponent {
        materializedTabs.add(tab.title)
        return try {
            val provider = providers[tab.title]
            provider?.createPanel(project)
                ?: EmptyStatePanel(project, tab.emptyMessage)
        } catch (e: Exception) {
            log.warn("[Workflow:UI] Failed to create ${tab.title} tab: ${e.message}", e)
            EmptyStatePanel(project, "Failed to load ${tab.title} tab.\n${e.message}")
        }
    }

    /**
     * Lightweight placeholder panel used for lazy tab loading (C-05).
     * Shown briefly while the real tab is being materialized on first selection.
     */
    private class LazyTabPlaceholder : JPanel(BorderLayout()) {
        init {
            val label = JBLabel("Loading...")
            label.horizontalAlignment = SwingConstants.CENTER
            label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            add(label, BorderLayout.CENTER)
        }
    }

    private fun settingsSnapshot(project: Project): String {
        val settings = PluginSettings.getInstance(project)
        val s = settings.state
        val c = settings.connections
        return buildString {
            // Connection URLs
            append("${c.jiraUrl}|${c.bambooUrl}|${c.sonarUrl}|${c.bitbucketUrl}|${c.sourcegraphUrl}")
            // Board config
            append("|board=${s.jiraBoardId}|boardType=${s.jiraBoardType}|boardName=${s.jiraBoardName}")
            // Keys that affect tab content
            append("|planKey=${s.bambooPlanKey}|sonarKey=${s.sonarProjectKey}")
            // Agent tab depends on extension providers being loaded
            append("|extProviders=${WorkflowTabProvider.EP_NAME.extensionList.size}")
        }
    }

    private data class DefaultTab(val title: String, val order: Int, val emptyMessage: String)
}

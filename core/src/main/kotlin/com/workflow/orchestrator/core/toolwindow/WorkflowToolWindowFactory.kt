package com.workflow.orchestrator.core.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
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
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.ui.StatusColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class WorkflowToolWindowFactory : ToolWindowFactory, DumbAware {

    private val log = Logger.getInstance(WorkflowToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        buildTabs(project, toolWindow)
        setupTitleActions(project, toolWindow)
        setupGearActions(project, toolWindow)
        setupActiveTicketBar(project, toolWindow)

        // Rebuild tabs when the tool window is shown, so settings changes take effect
        project.messageBus.connect(toolWindow.disposable)
            .subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                private var lastSettingsSnapshot = settingsSnapshot(project)

                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id != toolWindow.id) return
                    val current = settingsSnapshot(project)
                    if (current != lastSettingsSnapshot) {
                        lastSettingsSnapshot = current
                        val selectedTab = toolWindow.contentManager.selectedContent?.displayName
                        buildTabs(project, toolWindow)
                        // Restore previously selected tab
                        if (selectedTab != null) {
                            toolWindow.contentManager.contents
                                .firstOrNull { it.displayName == selectedTab }
                                ?.let { toolWindow.contentManager.setSelectedContent(it) }
                        }
                    }
                }
            })
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
                    // Switch to Sprint tab (index 0)
                    val cm = toolWindow.contentManager
                    val sprintTab = cm.contents.firstOrNull { it.displayName == "Sprint" }
                    if (sprintTab != null) {
                        cm.setSelectedContent(sprintTab)
                    }
                }
            })
        }

        // Add the bar above the tool window content
        val twComponent = toolWindow.component
        twComponent.add(bar, BorderLayout.NORTH)

        // Initialize from persisted settings
        val settings = PluginSettings.getInstance(project)
        val activeId = settings.state.activeTicketId
        if (!activeId.isNullOrBlank()) {
            ticketLabel.text = activeId
            summaryLabel.text = settings.state.activeTicketSummary ?: ""
            bar.isVisible = true
        }

        // Subscribe to TicketChanged events
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventBus = project.service<EventBus>()

        scope.launch {
            eventBus.events.filterIsInstance<WorkflowEvent.TicketChanged>().collect { event ->
                invokeLater {
                    if (event.ticketId.isNotBlank()) {
                        ticketLabel.text = event.ticketId
                        summaryLabel.text = event.ticketSummary
                        bar.isVisible = true
                    } else {
                        bar.isVisible = false
                    }
                    bar.parent?.revalidate()
                    bar.parent?.repaint()
                }
            }
        }

        // Cancel coroutine scope when tool window is disposed
        toolWindow.disposable.let { disposable ->
            com.intellij.openapi.util.Disposer.register(disposable) {
                scope.cancel()
            }
        }
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
                    val component = content?.component
                    if (component is Refreshable) {
                        (component as Refreshable).refresh()
                    }
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
            object : DumbAwareAction("Settings", "Open Workflow Orchestrator settings", AllIcons.General.GearPlain) {
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

    private fun setupGearActions(project: Project, toolWindow: ToolWindow) {
        val gearGroup = DefaultActionGroup().apply {
            // Settings shortcut
            add(object : DumbAwareAction("Settings", "Configure Workflow Orchestrator", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "General")
                }
            })

            addSeparator()

            // Quick toggle modules
            add(object : DumbAwareAction("Refresh All Tabs", "Rebuild all tabs with latest settings", AllIcons.Actions.ForceRefresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    val selectedTab = toolWindow.contentManager.selectedContent?.displayName
                    buildTabs(project, toolWindow)
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

    // Tracks which tabs have been materialized (created for real).
    // Reset on each buildTabs() call so settings changes re-create everything.
    private val materializedTabs = mutableSetOf<String>()

    private fun buildTabs(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        contentManager.removeAllContents(true)
        materializedTabs.clear()

        val providers = WorkflowTabProvider.EP_NAME.extensionList
            .sortedBy { it.order }
            .associateBy { it.tabTitle }

        val defaultTabs = listOf(
            DefaultTab("Sprint", 0, "No tickets assigned.\nConnect to Jira in Settings to get started."),
            DefaultTab("PR", 1, "No pull requests found.\nConnect to Bitbucket in Settings."),
            DefaultTab("Build", 2, "No builds found.\nPush your changes to trigger a CI build."),
            DefaultTab("Quality", 3, "No quality data available.\nConnect to SonarQube in Settings."),
            DefaultTab("Automation", 4, "Automation suite not configured.\nSet up Bamboo in Settings."),
            DefaultTab("Handover", 5, "No active task to hand over.\nStart work on a ticket first.")
        )

        defaultTabs.forEach { tab ->
            val isFirstTab = tab.order == 0
            val panel = if (isFirstTab) {
                // Eagerly create the first/default tab so the user sees content immediately
                materializeTab(project, tab, providers)
            } else {
                // Lightweight placeholder -- real panel created on first selection
                LazyTabPlaceholder()
            }
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            contentManager.addContent(content)
        }

        // Listen for tab selection to materialize lazy tabs on demand
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                val content = event.content
                val tabTitle = content.displayName
                if (event.operation == ContentManagerEvent.ContentOperation.add
                    && tabTitle !in materializedTabs
                ) {
                    val tab = defaultTabs.firstOrNull { it.title == tabTitle } ?: return
                    val realPanel = materializeTab(project, tab, providers)
                    content.component = realPanel
                    log.info("[Workflow:UI] Lazy-loaded tab: $tabTitle")
                }
            }
        })
    }

    /**
     * Creates the real panel for a tab and marks it as materialized.
     */
    private fun materializeTab(
        project: Project,
        tab: DefaultTab,
        providers: Map<String, WorkflowTabProvider>
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
            append("${c.jiraUrl}|${c.bambooUrl}|${c.sonarUrl}|${c.bitbucketUrl}|${c.sourcegraphUrl}|${c.nexusUrl}")
            // Board config
            append("|board=${s.jiraBoardId}|boardType=${s.jiraBoardType}|boardName=${s.jiraBoardName}")
            // Module toggles
            append("|sprint=${s.sprintModuleEnabled}|build=${s.buildModuleEnabled}")
            append("|quality=${s.qualityModuleEnabled}|auto=${s.automationModuleEnabled}|handover=${s.handoverModuleEnabled}")
            // Keys that affect tab content
            append("|planKey=${s.bambooPlanKey}|sonarKey=${s.sonarProjectKey}")
        }
    }

    private data class DefaultTab(val title: String, val order: Int, val emptyMessage: String)
}

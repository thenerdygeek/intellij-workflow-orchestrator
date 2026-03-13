package com.workflow.orchestrator.core.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.workflow.orchestrator.core.settings.PluginSettings

class WorkflowToolWindowFactory : ToolWindowFactory, DumbAware {

    private val log = Logger.getInstance(WorkflowToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        buildTabs(project, toolWindow)
        setupTitleActions(project, toolWindow)
        setupGearActions(project, toolWindow)

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
    // Title bar actions (small icon buttons at top of tool window)
    // ---------------------------------------------------------------

    private fun setupTitleActions(project: Project, toolWindow: ToolWindow) {
        toolWindow.setTitleActions(listOf(
            // Refresh current tab
            object : DumbAwareAction("Refresh", "Reload data for the current tab", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    val selectedTab = toolWindow.contentManager.selectedContent?.displayName
                    log.info("[Workflow:UI] Refresh requested for tab: $selectedTab")
                    // Rebuild just the selected tab by rebuilding all tabs
                    val snapshot = settingsSnapshot(project)
                    buildTabs(project, toolWindow)
                    if (selectedTab != null) {
                        toolWindow.contentManager.contents
                            .firstOrNull { it.displayName == selectedTab }
                            ?.let { toolWindow.contentManager.setSelectedContent(it) }
                    }
                }
            },

            // Open active ticket in Jira
            object : DumbAwareAction("Open in Jira", "Open active ticket in browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val ticketId = settings.state.activeTicketId
                    val jiraUrl = settings.state.jiraUrl
                    if (!ticketId.isNullOrBlank() && !jiraUrl.isNullOrBlank()) {
                        BrowserUtil.browse("${jiraUrl.trimEnd('/')}/browse/$ticketId")
                    }
                }

                override fun update(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val hasTicket = !settings.state.activeTicketId.isNullOrBlank()
                    val hasUrl = !settings.state.jiraUrl.isNullOrBlank()
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
            add(object : DumbAwareAction("Connections Settings", "Configure service URLs and tokens", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, "Connections")
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
                    val jiraUrl = settings.state.jiraUrl
                    if (!jiraUrl.isNullOrBlank()) {
                        BrowserUtil.browse("${jiraUrl.trimEnd('/')}/secure/RapidBoard.jspa")
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !PluginSettings.getInstance(project).state.jiraUrl.isNullOrBlank()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            // Open SonarQube in browser
            add(object : DumbAwareAction("Open SonarQube", "Open SonarQube dashboard in browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val sonarUrl = settings.state.sonarUrl
                    if (!sonarUrl.isNullOrBlank()) {
                        BrowserUtil.browse(sonarUrl.trimEnd('/'))
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !PluginSettings.getInstance(project).state.sonarUrl.isNullOrBlank()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })

            // Open Bamboo in browser
            add(object : DumbAwareAction("Open Bamboo", "Open Bamboo build dashboard in browser", AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = PluginSettings.getInstance(project)
                    val bambooUrl = settings.state.bambooUrl
                    if (!bambooUrl.isNullOrBlank()) {
                        BrowserUtil.browse(bambooUrl.trimEnd('/'))
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !PluginSettings.getInstance(project).state.bambooUrl.isNullOrBlank()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }
        toolWindow.setAdditionalGearActions(gearGroup)
    }

    // ---------------------------------------------------------------
    // Tab building
    // ---------------------------------------------------------------

    private fun buildTabs(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        contentManager.removeAllContents(true)

        val providers = WorkflowTabProvider.EP_NAME.extensionList
            .sortedBy { it.order }
            .associateBy { it.tabTitle }

        val defaultTabs = listOf(
            DefaultTab("Sprint", 0, "No tickets assigned.\nConnect to Jira in Settings to get started."),
            DefaultTab("Build", 1, "No builds found.\nPush your changes to trigger a CI build."),
            DefaultTab("Quality", 2, "No quality data available.\nConnect to SonarQube in Settings."),
            DefaultTab("Automation", 3, "Automation suite not configured.\nSet up Bamboo in Settings."),
            DefaultTab("Handover", 4, "No active task to hand over.\nStart work on a ticket first.")
        )

        defaultTabs.forEach { tab ->
            val panel = try {
                val provider = providers[tab.title]
                provider?.createPanel(project)
                    ?: EmptyStatePanel(project, tab.emptyMessage)
            } catch (e: Exception) {
                log.warn("[Workflow:UI] Failed to create ${tab.title} tab: ${e.message}", e)
                EmptyStatePanel(project, "Failed to load ${tab.title} tab.\n${e.message}")
            }
            val content = ContentFactory.getInstance().createContent(panel, tab.title, false)
            content.isCloseable = false
            contentManager.addContent(content)
        }
    }

    private fun settingsSnapshot(project: Project): String {
        val s = PluginSettings.getInstance(project).state
        return buildString {
            // Connection URLs
            append("${s.jiraUrl}|${s.bambooUrl}|${s.sonarUrl}|${s.bitbucketUrl}|${s.sourcegraphUrl}|${s.nexusUrl}")
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

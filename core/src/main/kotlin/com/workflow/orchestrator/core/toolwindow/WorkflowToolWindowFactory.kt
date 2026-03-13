package com.workflow.orchestrator.core.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
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

        // Rebuild tabs when the tool window is shown, so settings changes take effect
        // without requiring an IDE restart
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
        return "${s.jiraUrl}|${s.bambooUrl}|${s.sonarUrl}|${s.bitbucketUrl}|${s.sourcegraphUrl}|${s.nexusUrl}"
    }

    private data class DefaultTab(val title: String, val order: Int, val emptyMessage: String)
}

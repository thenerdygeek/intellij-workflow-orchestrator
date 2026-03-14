package com.workflow.orchestrator.handover.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import javax.swing.Icon
import javax.swing.JComponent

typealias PanelSwitcher = (String) -> Unit

class HandoverToolbar(private val panelSwitcher: PanelSwitcher) {

    companion object {
        const val PANEL_COPYRIGHT = "copyright"
        const val PANEL_CODY = "cody"
        const val PANEL_JIRA = "jira"
        const val PANEL_TIME = "time"
        const val PANEL_QA = "qa"
        const val PANEL_MACRO = "macro"
    }

    fun createToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(toolbarAction("Copyright", AllIcons.Nodes.CopyOfFolder, PANEL_COPYRIGHT))
            add(toolbarAction("Cody Review", AllIcons.Actions.Preview, PANEL_CODY))
            add(toolbarAction("Jira Comment", AllIcons.Toolwindows.ToolWindowMessages, PANEL_JIRA))
            add(toolbarAction("Time Log", AllIcons.Actions.Profile, PANEL_TIME))
            add(toolbarAction("QA Clipboard", AllIcons.Actions.Copy, PANEL_QA))
            add(toolbarAction("Macro", AllIcons.Actions.Lightning, PANEL_MACRO))
        }

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("HandoverToolbar", group, true)
        toolbar.targetComponent = toolbar.component
        return toolbar.component
    }

    private fun toolbarAction(text: String, icon: Icon, panelId: String): AnAction {
        return object : AnAction(text, "Show $text panel", icon) {
            override fun actionPerformed(e: AnActionEvent) {
                panelSwitcher(panelId)
            }
        }
    }
}

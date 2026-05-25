// Copyright 2024-2026 Workflow Orchestrator Project. All rights reserved.
package com.workflow.orchestrator.agent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.workflow.orchestrator.core.util.ProjectIdentifier
import java.nio.file.Files

/**
 * Opens the OS file browser at the research sub-agent's dump directory
 * (`~/.workflow-orchestrator/{proj}/agent/research/`).
 *
 * Registered under Tools > Workflow Orchestrator via plugin.xml — matching the
 * same ToolsMenu pattern used by [OpenToolDocsAction] and GenerateReportAction.
 * The Agent tab toolbar is full-JCEF-rendered, so a menu action is the least
 * invasive integration point.
 */
class OpenResearchFolderAction :
    AnAction(
        "Open Research Folder",
        "Browse research dumps written by the research sub-agent",
        AllIcons.Nodes.Folder,
    ),
    DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val researchDir = ProjectIdentifier.researchDir(basePath)
        try {
            if (!researchDir.exists()) Files.createDirectories(researchDir.toPath())
            java.awt.Desktop.getDesktop().open(researchDir)
        } catch (t: Throwable) {
            Messages.showWarningDialog(
                project,
                "Couldn't open the research folder: ${t.message}",
                "Open Research Folder",
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }
}

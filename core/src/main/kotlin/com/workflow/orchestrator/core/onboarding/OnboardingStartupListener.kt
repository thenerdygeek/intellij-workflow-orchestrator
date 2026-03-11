package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.GotItTooltip
import java.awt.Point

class OnboardingStartupListener : ProjectActivity {

    override suspend fun execute(project: Project) {
        val onboarding = OnboardingService.getInstance(project)
        if (!onboarding.shouldShowOnboarding()) return

        // Show GotItTooltip anchored to the Workflow tool window stripe button
        com.intellij.openapi.application.invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow") ?: return@invokeLater

            val tooltip = GotItTooltip(
                "workflow.orchestrator.onboarding",
                "Connect your development tools to get started.",
                project
            )
            tooltip.withHeader("Welcome to Workflow Orchestrator!")
            tooltip.withLink("Start Setup") {
                onboarding.showSetupDialog()
            }

            toolWindow.component?.let { component ->
                tooltip.show(component, GotItTooltip.BOTTOM_MIDDLE)
            }

            onboarding.markOnboardingShown()
        }
    }
}

package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.GotItTooltip
import com.workflow.orchestrator.core.settings.PluginSettings
import java.awt.Point

class OnboardingStartupListener : ProjectActivity {

    private val log = Logger.getInstance(OnboardingStartupListener::class.java)

    override suspend fun execute(project: Project) {
        // Migrate per-project URLs to global ConnectionSettings (one-time)
        PluginSettings.getInstance(project).migrateUrlsToGlobalIfNeeded()

        val onboarding = OnboardingService.getInstance(project)
        if (!onboarding.shouldShowOnboarding()) return

        log.info("[Core:Onboarding] Showing GotItTooltip for first-run onboarding")

        // Show GotItTooltip anchored to the Workflow tool window stripe button
        com.intellij.openapi.application.invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Workflow")
            if (toolWindow == null) {
                log.warn("[Core:Onboarding] Workflow tool window not found, skipping tooltip")
                return@invokeLater
            }

            val tooltip = GotItTooltip(
                "workflow.orchestrator.onboarding",
                "Connect your development tools to get started.",
                project
            )
            tooltip.withHeader("Welcome to Workflow Orchestrator!")
            tooltip.withLink("Start Setup") {
                log.info("[Core:Onboarding] User clicked 'Start Setup' from onboarding tooltip")
                onboarding.showSetupDialog()
            }

            toolWindow.component?.let { component ->
                tooltip.show(component, GotItTooltip.BOTTOM_MIDDLE)
                log.info("[Core:Onboarding] GotItTooltip displayed successfully")
            }

            onboarding.markOnboardingShown()
        }
    }
}

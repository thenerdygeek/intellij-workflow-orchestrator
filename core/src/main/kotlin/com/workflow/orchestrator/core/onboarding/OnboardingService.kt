package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings

@Service(Service.Level.PROJECT)
class OnboardingService(private val project: Project) {

    var hasShownOnboarding: Boolean = false
        private set

    fun shouldShowOnboarding(): Boolean {
        if (hasShownOnboarding) return false
        val settings = PluginSettings.getInstance(project)
        return !settings.isAnyServiceConfigured
    }

    fun markOnboardingShown() {
        hasShownOnboarding = true
    }

    fun showSetupDialog() {
        val dialog = SetupDialog(project)
        dialog.show()
        markOnboardingShown()
    }

    companion object {
        fun getInstance(project: Project): OnboardingService {
            return project.service<OnboardingService>()
        }
    }
}

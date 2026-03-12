package com.workflow.orchestrator.core.onboarding

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings

@Service(Service.Level.PROJECT)
class OnboardingService(private val project: Project) {

    private val log = Logger.getInstance(OnboardingService::class.java)

    var hasShownOnboarding: Boolean = false
        private set

    fun shouldShowOnboarding(): Boolean {
        if (hasShownOnboarding) return false
        val settings = PluginSettings.getInstance(project)
        val shouldShow = !settings.isAnyServiceConfigured
        if (shouldShow) {
            log.info("[Core:Onboarding] First run detected — no services configured, onboarding should be shown")
        }
        return shouldShow
    }

    fun markOnboardingShown() {
        hasShownOnboarding = true
        log.info("[Core:Onboarding] Onboarding marked as shown")
    }

    fun showSetupDialog() {
        log.info("[Core:Onboarding] Opening setup dialog")
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

package com.workflow.orchestrator.bamboo.listeners

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.bamboo.service.BuildMonitorService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver

/**
 * Starts [BuildMonitorService] polling at project open so that [BuildLogReady]
 * events flow to consumers (e.g. Automation tab) regardless of whether the
 * Build tab has been opened. Waits for smart mode so Git APIs are available.
 */
class BuildMonitorStartupActivity : ProjectActivity {

    private val log = logger<BuildMonitorStartupActivity>()

    override suspend fun execute(project: Project) {
        DumbService.getInstance(project).runWhenSmart {
            val settings = PluginSettings.getInstance(project)

            // Resolve plan key: primary repo → global fallback
            val resolver = RepoContextResolver.getInstance(project)
            val repoConfig = resolver.getPrimary()
            val planKey = repoConfig?.bambooPlanKey?.takeIf { it.isNotBlank() }
                ?: settings.state.bambooPlanKey?.takeIf { it.isNotBlank() }

            if (planKey.isNullOrBlank()) {
                log.info("[Bamboo:Startup] No Bamboo plan key configured — skipping build monitor")
                return@runWhenSmart
            }

            // Resolve current git branch
            val branch = resolver.resolvePrimaryGitRepo()?.currentBranchName ?: "develop"
            val interval = settings.state.buildPollIntervalSeconds.toLong() * 1000

            log.info("[Bamboo:Startup] Starting build monitor: planKey=$planKey, branch=$branch, interval=${interval}ms")
            BuildMonitorService.getInstance(project).startPolling(planKey, branch, interval)
        }
    }
}

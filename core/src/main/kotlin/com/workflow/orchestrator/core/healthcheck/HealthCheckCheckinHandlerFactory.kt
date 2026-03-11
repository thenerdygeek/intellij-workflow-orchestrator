package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.VcsCheckinHandlerFactory
import com.workflow.orchestrator.core.healthcheck.checks.HealthCheckContext
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.GitVcs
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.runBlocking

class HealthCheckCheckinHandlerFactory : VcsCheckinHandlerFactory(GitVcs.getKey()) {

    override fun createVcsHandler(
        panel: CheckinProjectPanel,
        commitContext: CommitContext
    ): CheckinHandler {
        return HealthCheckCheckinHandler(panel)
    }
}

class HealthCheckCheckinHandler(
    private val panel: CheckinProjectPanel
) : CheckinHandler() {

    override fun beforeCheckin(): ReturnResult {
        val project = panel.project
        val settings = PluginSettings.getInstance(project).state
        val mode = settings.healthCheckBlockingMode

        if (!settings.healthCheckEnabled || mode == "off") return ReturnResult.COMMIT

        val currentBranch = GitRepositoryManager.getInstance(project)
            .repositories.firstOrNull()?.currentBranchName ?: ""

        val changedFiles = panel.selectedChanges
            .mapNotNull { it.virtualFile }

        val context = HealthCheckContext(
            project = project,
            changedFiles = changedFiles,
            commitMessage = panel.commitMessage ?: "",
            branch = currentBranch
        )

        // Task.Modal.run() executes on a pooled background thread, not the EDT.
        // runBlocking is safe here because the entire coroutine chain (HealthCheckService,
        // MavenBuildService, EventBus.emit) uses Dispatchers.IO or suspend-only calls —
        // none dispatch to the EDT. The modal dialog blocks the EDT visually (progress bar)
        // but does not hold the EDT thread itself.
        var healthResult: HealthCheckResult? = null
        ProgressManager.getInstance().run(object : Task.Modal(
            project, "Running Health Checks...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                healthResult = runBlocking {
                    HealthCheckService.getInstance(project).runChecks(context)
                }
            }
        })

        val result = healthResult ?: return ReturnResult.COMMIT
        if (result.skipped || result.passed) return ReturnResult.COMMIT

        val failedChecks = result.checkResults
            .filter { !it.value.passed }
            .map { "${it.key}: ${it.value.message}" }
        val summary = failedChecks.joinToString("\n")

        return when (mode) {
            "hard" -> {
                WorkflowNotificationService.getInstance(project).notifyError(
                    "workflow.healthcheck",
                    "Health Check Failed",
                    summary
                )
                ReturnResult.CANCEL
            }
            "soft" -> {
                WorkflowNotificationService.getInstance(project).notifyWarning(
                    "workflow.healthcheck",
                    "Health Check Warning",
                    "$summary\n\nCommit proceeding with warnings."
                )
                ReturnResult.COMMIT
            }
            else -> ReturnResult.COMMIT
        }
    }
}

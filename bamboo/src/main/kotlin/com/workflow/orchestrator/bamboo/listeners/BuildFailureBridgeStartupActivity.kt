package com.workflow.orchestrator.bamboo.listeners

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.notifications.WorkflowNotificationService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * Bamboo→Bitbucket bridge (R-ADD-5 / R-ADD-12, audit 2026-05-07).
 *
 * Subscribes to [WorkflowEvent.BuildFinished] events emitted by `BuildMonitorService`.
 * On a FAILED terminal build, it:
 *
 *  1. Asks Bamboo for the build's recorded VCS revision (commit SHA) via
 *     [BambooApiClient.getResultVcsRevision] (`?expand=vcsRevisions`).
 *  2. For each configured repo, calls
 *     [BitbucketBranchClient.getCommitPullRequests] to identify PRs
 *     containing that commit.
 *  3. Surfaces a single notification (group `workflow.pr`) listing the
 *     affected PR ids — the user clicks through to the PR tab from there.
 *
 * Lives in `:bamboo` (not `:pullrequest`) because the project's module-graph
 * rule forbids `:pullrequest → :bamboo`. The listener only consumes `:core`
 * APIs (`BitbucketBranchClient`, `EventBus`, `WorkflowNotificationService`)
 * plus its own module's [BambooApiClient], which keeps the dependency
 * direction clean.
 *
 * Source: docs/research/2026-05-07-bitbucket-recommendations.md §2 B1, §4 R-ADD-5.
 */
class BuildFailureBridgeStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(BuildFailureBridgeStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val eventBus = project.service<EventBus>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            eventBus.events
                .filterIsInstance<WorkflowEvent.BuildFinished>()
                .collect { event ->
                    if (event.status == WorkflowEvent.BuildEventStatus.FAILED) {
                        runCatching { handleFailedBuild(project, event) }
                            .onFailure { log.warn("[BuildFailureBridge] handler failed for ${event.planKey}-${event.buildNumber}", it) }
                    }
                }
        }
    }

    private suspend fun handleFailedBuild(project: Project, event: WorkflowEvent.BuildFinished) {
        val settings = PluginSettings.getInstance(project)
        val configuredRepos = settings.getRepos().filter { it.isConfigured }
        if (configuredRepos.isEmpty()) {
            log.info("[BuildFailureBridge] No configured repos — skipping bridge for ${event.planKey}-${event.buildNumber}")
            return
        }

        val sha = resolveCommitSha(project, event) ?: run {
            log.info("[BuildFailureBridge] No VCS revision recorded for ${event.planKey}-${event.buildNumber}; cannot map to PRs")
            return
        }

        val client = BitbucketBranchClient.fromConfiguredSettings() ?: run {
            log.info("[BuildFailureBridge] Bitbucket not configured — skipping PR notification")
            return
        }

        // Fan out across every configured repo. The same commit normally lives in
        // only one repo, but cheap parallel calls beat trying to derive the right
        // repo from Bamboo's repository name (which doesn't always match the slug).
        val affectedPrIds = mutableListOf<Pair<RepoConfig, Int>>()
        for (repo in configuredRepos) {
            val projectKey = repo.bitbucketProjectKey ?: continue
            val repoSlug = repo.bitbucketRepoSlug ?: continue
            when (val result = client.getCommitPullRequests(projectKey, repoSlug, sha)) {
                is ApiResult.Success -> {
                    result.data.values.forEach { pr -> affectedPrIds += repo to pr.id }
                }
                is ApiResult.Error -> {
                    log.debug("[BuildFailureBridge] Reverse-lookup miss for $projectKey/$repoSlug @$sha: ${result.message}")
                }
            }
        }

        if (affectedPrIds.isEmpty()) {
            log.info("[BuildFailureBridge] Build ${event.planKey}-${event.buildNumber} on $sha — no PRs reverse-mapped")
            return
        }

        val title = "Build failed — affects ${affectedPrIds.size} PR(s)"
        val content = buildString {
            append("${event.planKey} #${event.buildNumber} failed on commit ${sha.take(8)}.\n")
            append("Affected PRs: ")
            append(affectedPrIds.joinToString(", ") { (repo, prId) -> "${repo.displayLabel}#$prId" })
        }
        WorkflowNotificationService.getInstance(project).notifyWarning(
            WorkflowNotificationService.GROUP_PR,
            title,
            content,
        )
        log.info("[BuildFailureBridge] Notification fired: $title")
    }

    /**
     * Resolves the build's commit SHA via Bamboo's `?expand=vcsRevisions` query.
     * `event.buildNumber` is appended to `event.planKey` to form the result key.
     *
     * Returns null if Bamboo has no VCS revision recorded for this build (rare —
     * only if the plan has no source repository linked).
     */
    private suspend fun resolveCommitSha(project: Project, event: WorkflowEvent.BuildFinished): String? {
        val resultKey = "${event.planKey}-${event.buildNumber}"
        val client = bambooClientOrNull(project) ?: return null
        return when (val r = client.getResultVcsRevision(resultKey)) {
            is ApiResult.Success -> r.data?.takeIf { it.isNotBlank() }
            is ApiResult.Error -> {
                log.warn("[BuildFailureBridge] getResultVcsRevision($resultKey) failed: ${r.message}")
                null
            }
        }
    }

    private fun bambooClientOrNull(project: Project): BambooApiClient? {
        val settings = PluginSettings.getInstance(project)
        val baseUrl = settings.connections.bambooUrl.orEmpty().trimEnd('/')
        if (baseUrl.isBlank()) return null
        val timeouts = HttpClientFactory.timeoutsFromSettings(project)
        val credentialStore = CredentialStore()
        return BambooApiClient(
            baseUrl = baseUrl,
            tokenProvider = { credentialStore.getToken(ServiceType.BAMBOO) },
            connectTimeoutSeconds = timeouts.connectSeconds,
            readTimeoutSeconds = timeouts.readSeconds,
        )
    }
}

package com.workflow.orchestrator.jira.listeners

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.BranchChangeListener
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.jira.service.ActiveTicketService
import com.workflow.orchestrator.jira.service.DismissedBranchStore
import com.workflow.orchestrator.jira.service.JiraServiceImpl
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Listens for git branch changes and, when a Jira ticket ID can be parsed
 * from the new branch, resolves ticket details via [JiraServiceImpl] and
 * emits an [EventBus] event:
 *
 * - [WorkflowEvent.TicketDetected] — "banner-only" path, fired when the user
 *   has already dismissed this branch (consumed by the Sprint tab banner).
 * - [WorkflowEvent.TicketDetectedInteractive] — "please show popup" path,
 *   fired on first detection for a branch. Consumed by
 *   `TicketDetectionPresenter`, which owns all Swing interaction.
 *
 * This listener holds no state and touches no UI.
 */
class BranchChangeTicketDetector(private val project: Project) : BranchChangeListener, Disposable {

    private val log = Logger.getInstance(BranchChangeTicketDetector::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        Disposer.register(project, this)
    }

    override fun branchWillChange(branchName: String) {
        // No action needed before branch change
    }

    override fun branchHasChanged(branchName: String) {
        // Filter: only process branch changes for configured repos
        val gitRepos = GitRepositoryManager.getInstance(project).repositories
        val changedRepo = gitRepos.find { it.currentBranchName == branchName }
        if (changedRepo != null) {
            val resolver = RepoContextResolver.getInstance(project)
            val repoConfig = resolver.resolveFromGitRepo(changedRepo)
            if (repoConfig == null || !repoConfig.isConfigured) {
                log.debug("[Jira:Branch] Skipping unconfigured repo for branch '$branchName'")
                return
            }
        }

        val ticketId = ActiveTicketService.extractTicketIdFromBranch(branchName) ?: run {
            log.debug("[Jira:Branch] No ticket ID detected in branch '$branchName'")
            return
        }

        log.info("[Jira:Branch] Detected ticket $ticketId from branch '$branchName'")

        // Skip if the detected ticket is already the active ticket.
        // Use ActiveTicketService (synchronous local cache) rather than PluginSettings.state
        // to avoid the async lag between the in-memory service state and the persisted setting
        // that causes spurious TicketDetectedInteractive events on rapid branch switches.
        val currentActiveTicket = ActiveTicketService.getInstance(project).activeTicketId
        if (currentActiveTicket == ticketId) {
            log.debug("[Jira:Branch] Ticket $ticketId is already active, skipping detection")
            return
        }

        // Fetch ticket details from Jira in background, then dispatch via EventBus.
        scope.launch {
            val result = JiraServiceImpl.getInstance(project).getTicket(ticketId)
            if (result.isError) {
                log.warn("[Jira:Branch] Failed to fetch issue $ticketId: ${result.summary}")
                return@launch
            }
            val issue = result.data!!
            val summary = issue.summary
            val sprintName = issue.sprintName
            val assigneeName = issue.assignee

            val eventBus = project.getService(EventBus::class.java)
            val dismissedStore = DismissedBranchStore.getInstance(project)

            if (dismissedStore.isDismissed(branchName)) {
                log.info("[Jira:Branch] Branch '$branchName' was previously dismissed, showing banner only")
                eventBus.emit(
                    WorkflowEvent.TicketDetected(
                        ticketKey = ticketId,
                        ticketSummary = summary,
                        sprint = sprintName,
                        assignee = assigneeName,
                        branchName = branchName
                    )
                )
            } else {
                eventBus.emit(
                    WorkflowEvent.TicketDetectedInteractive(
                        ticketKey = ticketId,
                        ticketSummary = summary,
                        sprint = sprintName,
                        assignee = assigneeName,
                        branchName = branchName
                    )
                )
            }
        }
    }

    override fun dispose() {
        scope.cancel()
        log.info("[Jira:Branch] BranchChangeTicketDetector disposed")
    }
}

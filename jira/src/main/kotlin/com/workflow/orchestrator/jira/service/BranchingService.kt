package com.workflow.orchestrator.jira.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import com.workflow.orchestrator.core.services.jira.TransitionDialogOpener
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.EDT
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BranchingService(
    private val project: Project,
    private val apiClient: JiraApiClient,
    private val activeTicketService: ActiveTicketService
) {
    private val log = Logger.getInstance(BranchingService::class.java)

    /**
     * Fetches remote branches from Bitbucket for the Start Work dialog.
     */
    suspend fun fetchRemoteBranches(branchClient: BitbucketBranchClient, projectKey: String, repoSlug: String): ApiResult<List<BitbucketBranch>> {
        return branchClient.getBranches(projectKey, repoSlug)
    }

    /**
     * Generates a branch name from the configured pattern and issue details.
     * If aiSummary is provided, it replaces the {ai-summary} placeholder.
     */
    fun generateBranchName(
        issue: JiraIssue,
        branchPattern: String,
        maxSummaryLength: Int,
        aiSummary: String? = null
    ): String {
        return BranchNameValidator.generateBranchName(
            pattern = branchPattern,
            ticketId = issue.key,
            summary = issue.fields.summary,
            maxSummaryLength = maxSummaryLength,
            issueTypeName = issue.fields.issuetype?.name,
            aiSummary = aiSummary
        )
    }

    /**
     * Fetches branches linked to a Jira issue from the Development Panel.
     * Primary: Jira dev-status API. Fallback: search Bitbucket branches by ticket key.
     */
    suspend fun fetchLinkedBranches(
        issue: JiraIssue,
        allBranches: List<BitbucketBranch>
    ): List<String> {
        // Primary: Jira dev-status API
        val devResult = apiClient.getDevStatusBranches(issue.id)
        if (devResult is ApiResult.Success && devResult.data.isNotEmpty()) {
            val names = devResult.data.map { it.name }
            log.info("[Jira:Branch] Found ${names.size} linked branches from dev-status for ${issue.key}: $names")
            return names
        }

        // Fallback: search Bitbucket branches containing the ticket key
        log.info("[Jira:Branch] Dev-status returned no branches, falling back to Bitbucket search for ${issue.key}")
        val matching = allBranches
            .filter { it.displayId.contains(issue.key, ignoreCase = true) }
            .map { it.displayId }
        if (matching.isNotEmpty()) {
            log.info("[Jira:Branch] Found ${matching.size} matching branches in Bitbucket for ${issue.key}: $matching")
        }
        return matching
    }

    /**
     * Uses an existing branch: fetch from remote, checkout locally,
     * and transition the Jira ticket via the orchestrator.
     *
     * [localVcsRootPath], when non-null, pins the local checkout to that Git root —
     * used by the Start Work dialog to honor the user's repo dropdown selection
     * regardless of which repo the current editor is in. Falling back to editor
     * context was the root cause of the false "branch already exists" notification
     * when the selected repo and editor repo disagreed.
     */
    suspend fun useExistingBranch(
        issue: JiraIssue,
        branchName: String,
        localVcsRootPath: String? = null
    ): ApiResult<String> {
        log.info("[Jira:Branch] Using existing branch '$branchName' for ${issue.key} (pinned root: ${localVcsRootPath ?: "<editor-context>"})")

        try {
            val repositories = com.intellij.openapi.application.ReadAction.compute<List<git4idea.repo.GitRepository>, Throwable> {
                GitRepositoryManager.getInstance(project).repositories
            }
            if (repositories.isEmpty()) {
                return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
            }
            // Prefer the explicitly-pinned local VCS root (from Start Work dropdown).
            // Only fall back to editor-context resolution when no pin was provided.
            val repo: git4idea.repo.GitRepository = repositories.find { it.root.path == localVcsRootPath }
                ?: run {
                    val resolver = RepoContextResolver.getInstance(project)
                    val editorFile = withContext(Dispatchers.EDT) {
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor?.file
                    }
                    val repoConfig = com.intellij.openapi.application.ReadAction.compute<com.workflow.orchestrator.core.settings.RepoConfig?, Throwable> {
                        if (editorFile != null) resolver.resolveFromFile(editorFile) else resolver.getPrimary()
                    }
                    if (repoConfig?.localVcsRootPath != null) {
                        repositories.find { it.root.path == repoConfig.localVcsRootPath }
                    } else null
                }
                ?: repositories.first()
            val git = Git.getInstance()

            // Fetch to update remote tracking refs (safe, doesn't touch local branches)
            val fetchResult = git.fetch(repo, repo.remotes.first(), emptyList())
            if (!fetchResult.success()) {
                log.warn("[Jira:Branch] Git fetch returned warnings: ${fetchResult.errorOutputAsJoinedString}")
            }

            // Check if branch exists locally
            val localBranch = repo.branches.findLocalBranch(branchName)
            if (localBranch != null) {
                // Branch exists locally — just checkout (preserves local commits)
                GitBrancher.getInstance(project).checkout(
                    branchName,
                    false,
                    listOf(repo),
                    null
                )
                log.info("[Jira:Branch] Checked out existing local branch '$branchName'")
            } else {
                // Branch only on remote — create local tracking branch
                GitBrancher.getInstance(project).checkoutNewBranchStartingFrom(
                    branchName,
                    "origin/$branchName",
                    listOf(repo),
                    null
                )
                log.info("[Jira:Branch] Checked out remote branch '$branchName' as new local tracking branch")
            }
        } catch (e: Exception) {
            log.error("[Jira:Branch] Failed to checkout existing branch: ${e.message}", e)
            return ApiResult.Error(
                ErrorType.SERVER_ERROR,
                "Failed to checkout branch '$branchName': ${e.message}",
                e
            )
        }

        // Transition ticket via orchestrator
        log.info("[Jira:Branch] Triggering start work transition for ${issue.key}")
        transitionToStartWorkStatus(issue.key)

        // Set active ticket
        activeTicketService.setActiveTicket(issue.key, issue.fields.summary)
        log.info("[Jira:Branch] Start work completed for ${issue.key} on existing branch $branchName")

        return ApiResult.Success(branchName)
    }

    /**
     * Creates a branch on Bitbucket, fetches it locally, checks it out,
     * and transitions the Jira ticket via the orchestrator.
     *
     * [localVcsRootPath], when non-null, pins the local checkout to that Git root —
     * used by the Start Work dialog to honor the user's repo dropdown selection
     * regardless of which repo the current editor is in.
     */
    suspend fun startWork(
        issue: JiraIssue,
        branchName: String,
        sourceBranch: String,
        branchClient: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String,
        localVcsRootPath: String? = null
    ): ApiResult<String> {
        log.info("[Jira:Branch] Creating remote branch '$branchName' from '$sourceBranch' for ${issue.key} in $projectKey/$repoSlug (pinned root: ${localVcsRootPath ?: "<editor-context>"})")

        // 1. Create branch on Bitbucket
        val createResult = branchClient.createBranch(projectKey, repoSlug, branchName, sourceBranch)
        when (createResult) {
            is ApiResult.Error -> {
                log.error("[Jira:Branch] Failed to create remote branch: ${createResult.message}")
                return createResult
            }
            is ApiResult.Success -> {
                log.info("[Jira:Branch] Remote branch '${createResult.data.displayId}' created")
            }
        }

        // 2. Fetch and checkout locally
        try {
            val repositories = com.intellij.openapi.application.ReadAction.compute<List<git4idea.repo.GitRepository>, Throwable> {
                GitRepositoryManager.getInstance(project).repositories
            }
            if (repositories.isEmpty()) {
                return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
            }
            // Prefer the explicitly-pinned local VCS root (from Start Work dropdown).
            // Falling back to editor context was the root cause of local checkout landing
            // in the wrong repo when the user picked a non-default module in the dialog.
            val repo: git4idea.repo.GitRepository = repositories.find { it.root.path == localVcsRootPath }
                ?: run {
                    val resolver = RepoContextResolver.getInstance(project)
                    val editorFile2 = withContext(Dispatchers.EDT) {
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor?.file
                    }
                    val repoConfig = com.intellij.openapi.application.ReadAction.compute<com.workflow.orchestrator.core.settings.RepoConfig?, Throwable> {
                        if (editorFile2 != null) resolver.resolveFromFile(editorFile2) else resolver.getPrimary()
                    }
                    if (repoConfig?.localVcsRootPath != null) {
                        repositories.find { it.root.path == repoConfig.localVcsRootPath }
                    } else null
                }
                ?: repositories.first()
            val git = Git.getInstance()
            val fetchResult = git.fetch(repo, repo.remotes.first(), emptyList())
            if (!fetchResult.success()) {
                log.warn("[Jira:Branch] Git fetch returned warnings: ${fetchResult.errorOutputAsJoinedString}")
            }

            // Checkout the remote branch as a local tracking branch
            GitBrancher.getInstance(project).checkoutNewBranchStartingFrom(
                branchName,
                "origin/$branchName",
                listOf(repo),
                null
            )
            log.info("[Jira:Branch] Checked out '$branchName' locally")
        } catch (e: Exception) {
            log.error("[Jira:Branch] Failed to checkout branch locally: ${e.message}", e)
            return ApiResult.Error(
                ErrorType.SERVER_ERROR,
                "Branch created on Bitbucket but failed to checkout locally: ${e.message}",
                e
            )
        }

        // 3. Transition ticket via orchestrator
        log.info("[Jira:Branch] Triggering start work transition for ${issue.key}")
        transitionToStartWorkStatus(issue.key)

        // 4. Set active ticket
        activeTicketService.setActiveTicket(issue.key, issue.fields.summary)
        log.info("[Jira:Branch] Start work completed for ${issue.key} on branch $branchName")

        return ApiResult.Success(branchName)
    }

    /**
     * Orchestrates the Jira ticket transition when Start Work is triggered.
     *
     * Resolution order:
     * 1. Read [PluginSettings.ticketTransitionDefaultStartWorkStatusName]. If empty → skip.
     * 2. Fetch available transitions via [TicketTransitionService].
     * 3. Pick the transition whose [toStatus.name] matches the configured name (case-insensitive),
     *    or fall back to the first IN_PROGRESS-category transition. If none found → skip.
     * 4. If [PluginSettings.ticketTransitionAutoTransitionSilently] is true:
     *    - Call [TicketTransitionService.tryAutoTransition]. On success → show a balloon.
     *    - On [TransitionError.RequiresInteraction] → open [TransitionDialogOpener] on EDT.
     *    - On other error → log and show balloon with the error summary.
     * 5. If silent = false → open [TransitionDialogOpener] directly on EDT.
     */
    private suspend fun transitionToStartWorkStatus(issueKey: String) {
        val settings = PluginSettings.getInstance(project)
        val targetStatusName = settings.state.ticketTransitionDefaultStartWorkStatusName.orEmpty().trim()
        if (targetStatusName.isEmpty()) {
            log.info("[Jira:Branch] Start Work transition skipped: ticketTransitionDefaultStartWorkStatusName is empty")
            return
        }

        val transitionService = try {
            project.service<TicketTransitionService>()
        } catch (e: Exception) {
            log.warn("[Jira:Branch] TicketTransitionService not available: ${e.message}")
            return
        }

        val transitionsResult = transitionService.getAvailableTransitions(issueKey)
        if (transitionsResult.isError) {
            log.warn("[Jira:Branch] Failed to fetch transitions for $issueKey: ${transitionsResult.summary}")
            return
        }

        val transitions = transitionsResult.data.orEmpty()
        val target = transitions.find { it.toStatus.name.equals(targetStatusName, ignoreCase = true) }
            ?: transitions.find { it.toStatus.category == StatusCategory.IN_PROGRESS }

        if (target == null) {
            log.warn("[Jira:Branch] No matching transition for '$targetStatusName' on $issueKey. Available: ${transitions.joinToString { it.name }}")
            return
        }

        val targetId = target.id
        val statusName = target.toStatus.name

        if (settings.state.ticketTransitionAutoTransitionSilently) {
            val autoResult = transitionService.tryAutoTransition(issueKey, targetId)
            when {
                !autoResult.isError -> {
                    log.info("[Jira:Branch] Auto-transitioned $issueKey → $statusName")
                    invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("workflow.jira")
                            .createNotification(
                                "$issueKey → $statusName",
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                    }
                }
                autoResult.payload is TransitionError.RequiresInteraction -> {
                    log.info("[Jira:Branch] Transition for $issueKey requires interaction — opening dialog")
                    invokeLater {
                        project.service<TransitionDialogOpener>().open(project, issueKey, targetId)
                    }
                }
                else -> {
                    log.warn("[Jira:Branch] Auto-transition failed for $issueKey: ${autoResult.summary}")
                    invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("workflow.jira")
                            .createNotification(
                                "Transition failed",
                                autoResult.summary,
                                NotificationType.WARNING
                            )
                            .notify(project)
                    }
                }
            }
        } else {
            log.info("[Jira:Branch] Opening transition dialog for $issueKey (silent=false)")
            invokeLater {
                project.service<TransitionDialogOpener>().open(project, issueKey, targetId)
            }
        }
    }
}

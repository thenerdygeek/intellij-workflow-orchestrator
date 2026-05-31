package com.workflow.orchestrator.jira.service

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import com.workflow.orchestrator.core.services.jira.TransitionDialogOpener
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.intellij.openapi.diagnostic.Logger
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.repo.GitRepositoryManager

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
     * [localVcsRootPath] is the user-pinned Git root from the Start Work dialog.
     * It must match a known repository's `root.path`; if it does not, this
     * function returns `ApiResult.Error(NOT_FOUND, ...)` rather than silently
     * falling back to the editor's repo or the first known repo. Falling back
     * was the root cause of false "branch already exists" notifications and of
     * checkouts landing in the wrong module in multi-repo projects.
     */
    suspend fun useExistingBranch(
        issue: JiraIssue,
        branchName: String,
        localVcsRootPath: String? = null
    ): ApiResult<String> {
        log.info("[Jira:Branch] Using existing branch '$branchName' for ${issue.key} (pinned root: ${localVcsRootPath ?: "<none>"})")

        try {
            val repositories = readAction {
                GitRepositoryManager.getInstance(project).repositories
            }
            if (repositories.isEmpty()) {
                return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
            }
            // The Start Work dropdown is the authoritative repo signal; falling back to
            // editor context (or `repositories.first()`) silently checked out the wrong
            // module in multi-repo projects. Hard-error instead so the user can repick.
            val repo: git4idea.repo.GitRepository = repositories.find { it.root.path == localVcsRootPath }
                ?: return ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "Configured VCS root '$localVcsRootPath' not found — repick in Start Work dialog"
                )
            val git = Git.getInstance()

            // Fetch to update remote tracking refs (safe, doesn't touch local branches)
            val remote = repo.remotes.firstOrNull()
                ?: return ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "No Git remote configured for repository '${repo.root.path}'."
                )
            val fetchResult = git.fetch(repo, remote, emptyList())
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
     * [localVcsRootPath] is the user-pinned Git root from the Start Work dialog.
     * It must match a known repository's `root.path`; if it does not, this
     * function returns `ApiResult.Error(NOT_FOUND, ...)` rather than silently
     * falling back to the editor's repo or the first known repo (which would
     * land the local checkout in the wrong module).
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
        log.info("[Jira:Branch] Creating remote branch '$branchName' from '$sourceBranch' for ${issue.key} in $projectKey/$repoSlug (pinned root: ${localVcsRootPath ?: "<none>"})")

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
            val repositories = readAction {
                GitRepositoryManager.getInstance(project).repositories
            }
            if (repositories.isEmpty()) {
                return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
            }
            // The Start Work dropdown is the authoritative repo signal; falling back to
            // editor context (or `repositories.first()`) silently checked out the wrong
            // module in multi-repo projects. Hard-error instead so the user can repick.
            val repo: git4idea.repo.GitRepository = repositories.find { it.root.path == localVcsRootPath }
                ?: return ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "Configured VCS root '$localVcsRootPath' not found — repick in Start Work dialog"
                )
            val git = Git.getInstance()
            val remote = repo.remotes.firstOrNull()
                ?: return ApiResult.Error(
                    ErrorType.NOT_FOUND,
                    "No Git remote configured for repository '${repo.root.path}'."
                )
            val fetchResult = git.fetch(repo, remote, emptyList())
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
     * Opens the unified transition dialog after Start Work creates a branch.
     *
     * Resolution:
     * 1. Read [PluginSettings.ticketTransitionDefaultStartWorkStatusName]. If empty → skip
     *    the prompt entirely (user opted out).
     * 2. Fetch the ticket's available transitions to find the one whose `toStatus.name`
     *    matches the configured target (case-insensitive, exact-name only — no category
     *    fallback so we never silently swap "In Progress" for "In Review").
     * 3. Open [TransitionDialogOpener] with the matched transition pre-selected. If no
     *    match was found, the opener still opens the dialog with no pre-selection so the
     *    user can pick from the full list of valid transitions for the ticket's current
     *    state.
     *
     * The dialog is the single confirmation point — required fields are rendered for the
     * selected transition, the user can switch to any other available transition, and
     * nothing mutates Jira until they click Transition.
     */
    private suspend fun transitionToStartWorkStatus(issueKey: String) {
        val settings = PluginSettings.getInstance(project)
        val targetStatusName = settings.state.ticketTransitionDefaultStartWorkStatusName.orEmpty().trim()
        if (targetStatusName.isEmpty()) {
            log.info("[Jira:Branch] Start Work transition prompt skipped: ticketTransitionDefaultStartWorkStatusName is empty")
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
        if (transitions.isEmpty()) {
            log.info("[Jira:Branch] $issueKey has no available transitions from its current status — skipping prompt")
            return
        }

        val target = transitions.find { it.toStatus.name.equals(targetStatusName, ignoreCase = true) }
        if (target == null) {
            log.info(
                "[Jira:Branch] No transition with name '$targetStatusName' on $issueKey — opening dialog with no pre-selection. " +
                    "Available: ${transitions.joinToString { "${it.name}→${it.toStatus.name}" }}"
            )
        } else {
            log.info("[Jira:Branch] Opening transition dialog for $issueKey, pre-selecting '${target.toStatus.name}' (id=${target.id})")
        }

        invokeLater {
            project.service<TransitionDialogOpener>().open(project, issueKey, target?.id)
        }
    }
}

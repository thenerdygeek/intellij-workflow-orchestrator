package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.EDT
import com.workflow.orchestrator.core.settings.RepoContextResolver
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
     * and transition the Jira ticket to "In Progress".
     */
    suspend fun useExistingBranch(
        issue: JiraIssue,
        branchName: String
    ): ApiResult<String> {
        log.info("[Jira:Branch] Using existing branch '$branchName' for ${issue.key}")

        try {
            val resolver = RepoContextResolver.getInstance(project)
            // Get editor file on EDT, resolve repo config off-EDT
            val editorFile = withContext(Dispatchers.EDT) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor?.file
            }
            val repoConfig = com.intellij.openapi.application.ReadAction.compute<com.workflow.orchestrator.core.settings.RepoConfig?, Throwable> {
                if (editorFile != null) resolver.resolveFromFile(editorFile) else resolver.getPrimary()
            }
            val repositories = com.intellij.openapi.application.ReadAction.compute<List<git4idea.repo.GitRepository>, Throwable> {
                GitRepositoryManager.getInstance(project).repositories
            }
            if (repositories.isEmpty()) {
                return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
            }
            val repo = if (repoConfig?.localVcsRootPath != null) {
                repositories.find { it.root.path == repoConfig.localVcsRootPath }
            } else {
                repositories.firstOrNull()
            } ?: repositories.firstOrNull()!!
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

        // Transition ticket to "In Progress"
        log.info("[Jira:Branch] Transitioning ${issue.key} to In Progress")
        val transitionResult = transitionToInProgress(issue.key)
        if (transitionResult is ApiResult.Error) {
            log.warn("[Jira:Branch] Jira transition failed for ${issue.key}, but branch was checked out: ${transitionResult.message}")
        }

        // Set active ticket
        activeTicketService.setActiveTicket(issue.key, issue.fields.summary)
        log.info("[Jira:Branch] Start work completed for ${issue.key} on existing branch $branchName")

        return ApiResult.Success(branchName)
    }

    /**
     * Creates a branch on Bitbucket, fetches it locally, checks it out,
     * and transitions the Jira ticket to "In Progress".
     */
    suspend fun startWork(
        issue: JiraIssue,
        branchName: String,
        sourceBranch: String,
        branchClient: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String
    ): ApiResult<String> {
        log.info("[Jira:Branch] Creating remote branch '$branchName' from '$sourceBranch' for ${issue.key}")

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
            // Fetch from remote to get the new branch
            val resolver = RepoContextResolver.getInstance(project)
            val editorFile2 = withContext(Dispatchers.EDT) {
                com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor?.file
            }
            val repoConfig = com.intellij.openapi.application.ReadAction.compute<com.workflow.orchestrator.core.settings.RepoConfig?, Throwable> {
                if (editorFile2 != null) resolver.resolveFromFile(editorFile2) else resolver.getPrimary()
            }
            val repositories = com.intellij.openapi.application.ReadAction.compute<List<git4idea.repo.GitRepository>, Throwable> {
                GitRepositoryManager.getInstance(project).repositories
            }
            if (repositories.isEmpty()) {
                return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
            }
            val repo = if (repoConfig?.localVcsRootPath != null) {
                repositories.find { it.root.path == repoConfig.localVcsRootPath }
            } else {
                repositories.firstOrNull()
            } ?: repositories.firstOrNull()!!
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

        // 3. Transition ticket to "In Progress"
        log.info("[Jira:Branch] Transitioning ${issue.key} to In Progress")
        val transitionResult = transitionToInProgress(issue.key)
        if (transitionResult is ApiResult.Error) {
            log.warn("[Jira:Branch] Jira transition failed for ${issue.key}, but branch was created: ${transitionResult.message}")
        }

        // 4. Set active ticket
        activeTicketService.setActiveTicket(issue.key, issue.fields.summary)
        log.info("[Jira:Branch] Start work completed for ${issue.key} on branch $branchName")

        return ApiResult.Success(branchName)
    }

    private suspend fun transitionToInProgress(issueKey: String): ApiResult<Unit> {
        val transitionsResult = apiClient.getTransitions(issueKey)
        val transitions = when (transitionsResult) {
            is ApiResult.Success -> transitionsResult.data
            is ApiResult.Error -> {
                log.error("[Jira:Branch] Failed to fetch transitions for $issueKey: ${transitionsResult.message}")
                return transitionsResult
            }
        }

        val inProgressTransition = transitions.find {
            it.name.equals("In Progress", ignoreCase = true) ||
            it.toStatus.category == com.workflow.orchestrator.core.model.jira.StatusCategory.IN_PROGRESS
        } ?: run {
            log.warn("[Jira:Branch] No 'In Progress' transition available for $issueKey. Available: ${transitions.joinToString { it.name }}")
            return ApiResult.Error(ErrorType.NOT_FOUND, "No 'In Progress' transition available.")
        }

        log.info("[Jira:Branch] Found transition '${inProgressTransition.name}' (id=${inProgressTransition.id}) for $issueKey")
        return apiClient.transitionIssue(issueKey, inProgressTransition.id)
    }
}

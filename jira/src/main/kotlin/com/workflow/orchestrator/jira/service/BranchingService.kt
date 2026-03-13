package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.bitbucket.BitbucketBranch
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
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
     * If codySummary is provided, it replaces the {cody-summary} placeholder.
     */
    fun generateBranchName(
        issue: JiraIssue,
        branchPattern: String,
        maxSummaryLength: Int,
        codySummary: String? = null
    ): String {
        return BranchNameValidator.generateBranchName(
            pattern = branchPattern,
            ticketId = issue.key,
            summary = issue.fields.summary,
            maxSummaryLength = maxSummaryLength,
            issueTypeName = issue.fields.issuetype?.name,
            codySummary = codySummary
        )
    }

    /**
     * Creates a branch on Bitbucket, fetches it locally, checks it out,
     * and transitions the Jira ticket to "In Progress".
     *
     * @param issue The Jira issue to start work on
     * @param branchName The branch name (from the dialog)
     * @param sourceBranch The source branch to create from (from the dialog)
     * @param branchClient The Bitbucket branch client
     * @param projectKey Bitbucket project key
     * @param repoSlug Bitbucket repository slug
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
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) {
            return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
        }

        try {
            // Fetch from remote to get the new branch
            val repo = repositories.first()
            val git = Git.getInstance()
            val fetchResult = git.fetch(repo, repo.remotes.first(), emptyList())
            if (!fetchResult.success()) {
                log.warn("[Jira:Branch] Git fetch returned warnings: ${fetchResult.errorOutputAsJoinedString}")
            }

            // Checkout the remote branch as a local tracking branch
            GitBrancher.getInstance(project).checkoutNewBranchStartingFrom(
                branchName,
                "origin/$branchName",
                repositories,
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
            it.to.statusCategory?.key == "indeterminate"
        } ?: run {
            log.warn("[Jira:Branch] No 'In Progress' transition available for $issueKey. Available: ${transitions.joinToString { it.name }}")
            return ApiResult.Error(ErrorType.NOT_FOUND, "No 'In Progress' transition available.")
        }

        log.info("[Jira:Branch] Found transition '${inProgressTransition.name}' (id=${inProgressTransition.id}) for $issueKey")
        return apiClient.transitionIssue(issueKey, inProgressTransition.id)
    }
}

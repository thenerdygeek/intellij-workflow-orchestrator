package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.intellij.openapi.diagnostic.Logger
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager

class BranchingService(
    private val project: Project,
    private val apiClient: JiraApiClient,
    private val activeTicketService: ActiveTicketService
) {
    private val log = Logger.getInstance(BranchingService::class.java)

    suspend fun startWork(issue: JiraIssue, branchPattern: String): ApiResult<String> {
        log.info("[Jira:Branch] Resolving START_WORK intent for ticket ${issue.key}")

        // 1. Generate branch name
        val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
        val branchName = BranchNameValidator.generateBranchName(
            pattern = branchPattern,
            ticketId = issue.key,
            summary = issue.fields.summary,
            maxSummaryLength = settings.state.branchMaxSummaryLength
        )
        log.info("[Jira:Branch] Creating branch $branchName for ticket ${issue.key}")

        // 2. Create git branch
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) {
            log.error("[Jira:Branch] No Git repository found in project")
            return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
        }

        try {
            GitBrancher.getInstance(project).checkoutNewBranch(branchName, repositories)
            log.info("[Jira:Branch] Branch $branchName created and checked out successfully")
        } catch (e: Exception) {
            log.error("[Jira:Branch] Failed to create branch '$branchName': ${e.message}", e)
            return ApiResult.Error(
                ErrorType.SERVER_ERROR,
                "Failed to create branch '$branchName': ${e.message}",
                e
            )
        }

        // 3. Transition ticket to "In Progress"
        log.info("[Jira:Branch] Transitioning ${issue.key} to In Progress")
        val transitionResult = transitionToInProgress(issue.key)
        if (transitionResult is ApiResult.Error) {
            // Branch was created, just warn about transition failure
            // Don't fail the whole operation
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

package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager

class BranchingService(
    private val project: Project,
    private val apiClient: JiraApiClient,
    private val activeTicketService: ActiveTicketService
) {

    suspend fun startWork(issue: JiraIssue, branchPattern: String): ApiResult<String> {
        // 1. Generate branch name
        val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
        val branchName = BranchNameValidator.generateBranchName(
            pattern = branchPattern,
            ticketId = issue.key,
            summary = issue.fields.summary,
            maxSummaryLength = settings.state.branchMaxSummaryLength
        )

        // 2. Create git branch
        val repositories = GitRepositoryManager.getInstance(project).repositories
        if (repositories.isEmpty()) {
            return ApiResult.Error(ErrorType.NOT_FOUND, "No Git repository found in this project.")
        }

        try {
            GitBrancher.getInstance(project).checkoutNewBranch(branchName, repositories)
        } catch (e: Exception) {
            return ApiResult.Error(
                ErrorType.SERVER_ERROR,
                "Failed to create branch '$branchName': ${e.message}",
                e
            )
        }

        // 3. Transition ticket to "In Progress"
        val transitionResult = transitionToInProgress(issue.key)
        if (transitionResult is ApiResult.Error) {
            // Branch was created, just warn about transition failure
            // Don't fail the whole operation
        }

        // 4. Set active ticket
        activeTicketService.setActiveTicket(issue.key, issue.fields.summary)

        return ApiResult.Success(branchName)
    }

    private suspend fun transitionToInProgress(issueKey: String): ApiResult<Unit> {
        val transitionsResult = apiClient.getTransitions(issueKey)
        val transitions = when (transitionsResult) {
            is ApiResult.Success -> transitionsResult.data
            is ApiResult.Error -> return transitionsResult
        }

        val inProgressTransition = transitions.find {
            it.name.equals("In Progress", ignoreCase = true) ||
            it.to.statusCategory?.key == "indeterminate"
        } ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No 'In Progress' transition available.")

        return apiClient.transitionIssue(issueKey, inProgressTransition.id)
    }
}

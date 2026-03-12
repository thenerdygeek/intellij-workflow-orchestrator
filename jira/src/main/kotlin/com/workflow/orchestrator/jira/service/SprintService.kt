package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraSprint

class SprintService(private val apiClient: JiraApiClient) {

    private val log = Logger.getInstance(SprintService::class.java)

    var activeSprint: JiraSprint? = null
        private set

    private var cachedIssues: List<JiraIssue> = emptyList()

    suspend fun loadSprintIssues(boardId: Int? = null): ApiResult<List<JiraIssue>> {
        val resolvedBoardId = boardId ?: discoverBoardId()
            ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No Jira Scrum boards found. Create a board first.")

        log.info("[Jira:Sprint] Loading sprint issues for board $resolvedBoardId")

        val sprintResult = apiClient.getActiveSprints(resolvedBoardId)
        val sprint = when (sprintResult) {
            is ApiResult.Success -> sprintResult.data.firstOrNull()
            is ApiResult.Error -> return sprintResult
        } ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No active sprint found on this board.")

        activeSprint = sprint
        log.info("[Jira:Sprint] Active sprint: ${sprint.name} (id=${sprint.id})")

        val issuesResult = apiClient.getSprintIssues(sprint.id)
        if (issuesResult is ApiResult.Success) {
            cachedIssues = issuesResult.data
            log.info("[Jira:Sprint] Loaded ${cachedIssues.size} issues")
        }
        return issuesResult
    }

    fun getCachedIssues(): List<JiraIssue> = cachedIssues

    private suspend fun discoverBoardId(): Int? {
        log.info("[Jira:Sprint] Auto-discovering board...")
        val boardsResult = apiClient.getBoards()
        return when (boardsResult) {
            is ApiResult.Success -> {
                val board = boardsResult.data.firstOrNull()
                if (board != null) log.info("[Jira:Sprint] Discovered board: ${board.name} (id=${board.id})")
                else log.warn("[Jira:Sprint] No boards found")
                board?.id
            }
            is ApiResult.Error -> {
                log.error("[Jira:Sprint] Board discovery failed: ${boardsResult.message}")
                null
            }
        }
    }
}

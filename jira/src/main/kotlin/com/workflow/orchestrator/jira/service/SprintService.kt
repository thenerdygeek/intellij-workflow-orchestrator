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
            is ApiResult.Success -> {
                log.info("[Jira:Sprint] Found ${sprintResult.data.size} active sprint(s) on board $resolvedBoardId")
                sprintResult.data.forEach { s ->
                    log.info("[Jira:Sprint]   - ${s.name} (id=${s.id}, state=${s.state})")
                }
                sprintResult.data.firstOrNull()
            }
            is ApiResult.Error -> {
                log.warn("[Jira:Sprint] Failed to fetch sprints for board $resolvedBoardId: ${sprintResult.message}")
                return sprintResult
            }
        } ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No active sprint found on board $resolvedBoardId. Check that the board has an active sprint in Jira.")

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
        // Try scrum boards first (only scrum boards have sprints)
        val boardsResult = apiClient.getBoards(boardType = "scrum")
        return when (boardsResult) {
            is ApiResult.Success -> {
                val board = boardsResult.data.firstOrNull()
                if (board != null) {
                    log.info("[Jira:Sprint] Discovered scrum board: ${board.name} (id=${board.id})")
                } else {
                    log.warn("[Jira:Sprint] No scrum boards found, trying all boards...")
                    // Fall back to any board type
                    val allBoardsResult = apiClient.getBoards()
                    if (allBoardsResult is ApiResult.Success) {
                        val anyBoard = allBoardsResult.data.firstOrNull()
                        if (anyBoard != null) {
                            log.info("[Jira:Sprint] Found board: ${anyBoard.name} (id=${anyBoard.id}, type=${anyBoard.type})")
                            return anyBoard.id
                        }
                        log.warn("[Jira:Sprint] No boards found at all")
                    }
                }
                board?.id
            }
            is ApiResult.Error -> {
                log.error("[Jira:Sprint] Board discovery failed: ${boardsResult.message}")
                null
            }
        }
    }
}

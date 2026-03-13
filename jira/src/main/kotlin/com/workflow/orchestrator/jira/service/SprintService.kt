package com.workflow.orchestrator.jira.service

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraBoard
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import com.workflow.orchestrator.jira.api.dto.JiraSprint

class SprintService(private val apiClient: JiraApiClient) {

    private val log = Logger.getInstance(SprintService::class.java)

    var activeSprint: JiraSprint? = null
        private set

    var discoveredBoard: JiraBoard? = null
        private set

    private var cachedIssues: List<JiraIssue> = emptyList()

    /**
     * Load issues for the current board. Behavior depends on board type:
     * - Scrum boards: fetches active sprint issues
     * - Kanban/simple boards: fetches unresolved board issues (no sprint concept)
     *
     * @param boardId explicit board ID (from settings), or null to auto-discover
     * @param boardTypeFilter board type filter from settings ("scrum", "kanban", or "" for all)
     */
    suspend fun loadSprintIssues(
        boardId: Int? = null,
        boardTypeFilter: String = "",
        allUsers: Boolean = false,
        boardName: String? = null
    ): ApiResult<List<JiraIssue>> {
        // Step 1: Resolve the board
        val board = if (boardId != null && boardId > 0) {
            log.info("[Jira:Sprint] Using configured board ID: $boardId (type: $boardTypeFilter)")
            // Use saved board type directly — no need to re-fetch from API
            JiraBoard(boardId, boardName ?: "Board $boardId", boardTypeFilter.ifBlank { "scrum" }, null)
        } else {
            discoverBoard(boardTypeFilter)
                ?: return ApiResult.Error(ErrorType.NOT_FOUND, "No Jira boards found. Check your Jira connection and board type filter in Settings.")
        }

        discoveredBoard = board
        log.info("[Jira:Sprint] Using board: ${board.name} (id=${board.id}, type=${board.type})")

        // Step 2: Load issues based on board type
        return if (board.type == "scrum") {
            loadScrumBoardIssues(board.id, allUsers)
        } else {
            loadKanbanBoardIssues(board.id, allUsers)
        }
    }

    private suspend fun loadScrumBoardIssues(boardId: Int, allUsers: Boolean = false): ApiResult<List<JiraIssue>> {
        val sprintResult = apiClient.getActiveSprints(boardId)
        val sprint = when (sprintResult) {
            is ApiResult.Success -> {
                log.info("[Jira:Sprint] Found ${sprintResult.data.size} active sprint(s) on board $boardId")
                sprintResult.data.forEach { s ->
                    log.info("[Jira:Sprint]   - ${s.name} (id=${s.id}, state=${s.state})")
                }
                sprintResult.data.firstOrNull()
            }
            is ApiResult.Error -> {
                log.warn("[Jira:Sprint] Failed to fetch sprints for board $boardId: ${sprintResult.message}")
                // Fall back to board issues if sprint endpoint fails
                log.info("[Jira:Sprint] Falling back to board issues endpoint")
                return loadKanbanBoardIssues(boardId, allUsers)
            }
        }

        if (sprint == null) {
            log.info("[Jira:Sprint] No active sprint on scrum board $boardId, falling back to board issues")
            return loadKanbanBoardIssues(boardId, allUsers)
        }

        activeSprint = sprint
        log.info("[Jira:Sprint] Active sprint: ${sprint.name} (id=${sprint.id})")

        val issuesResult = apiClient.getSprintIssues(sprint.id, allUsers)
        if (issuesResult is ApiResult.Success) {
            cachedIssues = issuesResult.data
            log.info("[Jira:Sprint] Loaded ${cachedIssues.size} issues from sprint ${sprint.name}")
        }
        return issuesResult
    }

    private suspend fun loadKanbanBoardIssues(boardId: Int, allUsers: Boolean = false): ApiResult<List<JiraIssue>> {
        activeSprint = null
        log.info("[Jira:Sprint] Loading board issues (no sprint) for board $boardId")
        val issuesResult = apiClient.getBoardIssues(boardId, allUsers)
        if (issuesResult is ApiResult.Success) {
            cachedIssues = issuesResult.data
            log.info("[Jira:Sprint] Loaded ${cachedIssues.size} board issues")
        }
        return issuesResult
    }

    fun getCachedIssues(): List<JiraIssue> = cachedIssues

    private suspend fun discoverBoard(boardTypeFilter: String): JiraBoard? {
        log.info("[Jira:Sprint] Auto-discovering board (filter: '${boardTypeFilter.ifBlank { "all" }}')")

        // If user specified a board type, search for that
        if (boardTypeFilter.isNotBlank()) {
            val result = apiClient.getBoards(boardType = boardTypeFilter)
            if (result is ApiResult.Success && result.data.isNotEmpty()) {
                val board = result.data.first()
                log.info("[Jira:Sprint] Discovered $boardTypeFilter board: ${board.name} (id=${board.id})")
                return board
            }
            log.warn("[Jira:Sprint] No '$boardTypeFilter' boards found")
        }

        // Try all boards
        val allResult = apiClient.getBoards()
        return when (allResult) {
            is ApiResult.Success -> {
                if (allResult.data.isEmpty()) {
                    log.warn("[Jira:Sprint] No boards found at all")
                    return null
                }
                // Log all available boards for debugging
                log.info("[Jira:Sprint] Available boards:")
                allResult.data.forEach { b ->
                    log.info("[Jira:Sprint]   - ${b.name} (id=${b.id}, type=${b.type})")
                }
                // Prefer scrum boards (they have sprints), fall back to any
                val board = allResult.data.firstOrNull { it.type == "scrum" }
                    ?: allResult.data.first()
                log.info("[Jira:Sprint] Selected board: ${board.name} (id=${board.id}, type=${board.type})")
                board
            }
            is ApiResult.Error -> {
                log.error("[Jira:Sprint] Board discovery failed: ${allResult.message}")
                null
            }
        }
    }
}

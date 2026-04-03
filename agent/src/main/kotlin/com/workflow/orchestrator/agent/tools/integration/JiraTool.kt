package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated Jira meta-tool replacing 15 individual jira_* tools.
 *
 * Saves ~14,400 tokens per API call by collapsing all Jira operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: get_ticket, get_transitions, transition, comment, get_comments,
 *          log_work, get_worklogs, get_sprints, get_linked_prs, get_boards,
 *          get_sprint_issues, get_board_issues, search_issues, search_tickets,
 *          get_dev_branches, start_work, download_attachment
 */
class JiraTool : AgentTool {

    override val name = "jira"

    override val description = """
Jira ticket management — issues, sprints, boards, transitions, comments, time logging.

Actions and their parameters:
- get_ticket(key) → Full ticket details
- get_transitions(key) → Available status transitions
- transition(key, transition_id, comment?) → Move ticket to new status
- comment(key, body) → Add comment to ticket
- get_comments(key) → List comments
- log_work(key, time_spent, comment?) → Log time (format: '2h', '30m', '1h 30m')
- get_worklogs(key) → List work logs
- get_sprints(board_id) → List sprints for board
- get_boards(type?, name_filter?) → List boards (type: scrum|kanban)
- get_sprint_issues(sprint_id) → Issues in sprint
- get_board_issues(board_id) → Issues on board
- search_issues(text, max_results?) → JQL/text search (default 20 results)
- search_tickets(jql, max_results?) → Run raw JQL query
- get_linked_prs(key) → PRs linked to issue
- get_dev_branches(key) → Dev branches for issue
- start_work(key, branch_name, source_branch) → Create branch and start work
- download_attachment(key, attachment_id) → Download attachment content

key: Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue.
description optional: for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_ticket", "get_transitions", "transition", "comment", "get_comments",
                    "log_work", "get_worklogs", "get_sprints", "get_linked_prs", "get_boards",
                    "get_sprint_issues", "get_board_issues", "search_issues", "search_tickets",
                    "get_dev_branches", "start_work", "download_attachment"
                )
            ),
            "key" to ParameterProperty(
                type = "string",
                description = "Jira issue key (e.g. PROJ-123) — used across all actions that operate on a single issue"
            ),
            "transition_id" to ParameterProperty(
                type = "string",
                description = "Transition ID — for transition (use get_transitions first)"
            ),
            "body" to ParameterProperty(
                type = "string",
                description = "Comment body — for comment"
            ),
            "comment" to ParameterProperty(
                type = "string",
                description = "Optional comment — for transition, log_work"
            ),
            "time_spent" to ParameterProperty(
                type = "string",
                description = "Time in Jira format: '2h', '30m', '1h 30m' — for log_work"
            ),
            "board_id" to ParameterProperty(
                type = "string",
                description = "Board ID — for get_sprints, get_boards (optional filter), get_board_issues"
            ),
            "sprint_id" to ParameterProperty(
                type = "string",
                description = "Sprint ID — for get_sprint_issues"
            ),
            "type" to ParameterProperty(
                type = "string",
                description = "Board type filter: 'scrum' or 'kanban' — for get_boards"
            ),
            "name_filter" to ParameterProperty(
                type = "string",
                description = "Name filter — for get_boards"
            ),
            "text" to ParameterProperty(
                type = "string",
                description = "Search text — for search_issues"
            ),
            "jql" to ParameterProperty(
                type = "string",
                description = "Raw JQL query string — for search_tickets"
            ),
            "max_results" to ParameterProperty(
                type = "string",
                description = "Max results (default 20 for search_issues, default 8 for search_tickets)"
            ),
            "attachment_id" to ParameterProperty(
                type = "string",
                description = "Jira attachment ID — for download_attachment"
            ),
            "branch_name" to ParameterProperty(
                type = "string",
                description = "Branch name to create — for start_work"
            ),
            "source_branch" to ParameterProperty(
                type = "string",
                description = "Source branch — for start_work"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of this action shown to user in approval dialog — recommended for transition, comment, start_work"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val service = ServiceLookup.jira(project)
            ?: return ServiceLookup.notConfigured("Jira")

        return when (action) {
            "get_ticket" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                ToolValidation.validateJiraKey(key)?.let { return it }
                service.getTicket(key).toAgentToolResult()
            }

            "get_transitions" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                ToolValidation.validateJiraKey(key)?.let { return it }
                service.getTransitions(key).toAgentToolResult()
            }

            "transition" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                val transitionId = params["transition_id"]?.jsonPrimitive?.content
                    ?: return missingParam("transition_id")
                val comment = params["comment"]?.jsonPrimitive?.content
                ToolValidation.validateJiraKey(key)?.let { return it }

                // Pre-flight: verify ticket exists and capture current status
                val ticketResult = service.getTicket(key)
                if (ticketResult.isError) {
                    return ToolResult(
                        content = "Cannot transition: ticket $key not found.\n${ticketResult.summary}",
                        summary = "Ticket $key not found",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                val currentStatus = ticketResult.data.status

                // Best-effort pre-flight: verify transition is available from current state.
                // If getTransitions() itself fails (network/auth), we skip this check and let
                // service.transition() handle the error with a better message from the API.
                val transitionsResult = service.getTransitions(key)
                if (!transitionsResult.isError) {
                    val match = transitionsResult.data.find { it.id == transitionId }
                    if (match == null) {
                        val availableList = transitionsResult.data.joinToString("\n") { "  ID ${it.id}: ${it.name} → ${it.toStatus}" }
                        val content = "Cannot transition $key: transition ID '$transitionId' is not available from current status '$currentStatus'.\n\nAvailable transitions:\n$availableList"
                        return ToolResult(
                            content = content,
                            summary = "Invalid transition ID '$transitionId' from status '$currentStatus'",
                            tokenEstimate = TokenEstimator.estimate(content),
                            isError = true
                        )
                    }
                }

                val result = service.transition(key, transitionId, comment = comment)
                if (result.isError) {
                    result.toAgentToolResult()
                } else {
                    val content = "Transitioned $key from '$currentStatus'. ${result.summary}"
                    ToolResult(
                        content = content,
                        summary = "Transitioned $key",
                        tokenEstimate = TokenEstimator.estimate(content)
                    )
                }
            }

            "comment" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                val body = params["body"]?.jsonPrimitive?.content
                    ?: return missingParam("body")
                ToolValidation.validateJiraKey(key)?.let { return it }
                ToolValidation.validateNotBlank(body, "body")?.let { return it }

                // Pre-flight: verify ticket exists
                val ticketResult = service.getTicket(key)
                if (ticketResult.isError) {
                    return ToolResult(
                        content = "Cannot add comment: ticket $key not found.\n${ticketResult.summary}",
                        summary = "Ticket $key not found",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                service.addComment(key, body).toAgentToolResult()
            }

            "get_comments" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                ToolValidation.validateJiraKey(key)?.let { return it }
                service.getComments(key).toAgentToolResult()
            }

            "log_work" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                val timeSpent = params["time_spent"]?.jsonPrimitive?.content
                    ?: return missingParam("time_spent")
                val comment = params["comment"]?.jsonPrimitive?.content
                ToolValidation.validateJiraKey(key)?.let { return it }
                ToolValidation.validateTimeSpent(timeSpent)?.let { return it }

                // Pre-flight: verify ticket exists
                val ticketResult = service.getTicket(key)
                if (ticketResult.isError) {
                    return ToolResult(
                        content = "Cannot log work: ticket $key not found.\n${ticketResult.summary}",
                        summary = "Ticket $key not found",
                        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }
                service.logWork(key, timeSpent, comment).toAgentToolResult()
            }

            "get_worklogs" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                ToolValidation.validateJiraKey(issueKey)?.let { return it }
                service.getWorklogs(issueKey).toAgentToolResult()
            }

            "get_sprints" -> {
                val boardIdStr = params["board_id"]?.jsonPrimitive?.content
                    ?: return missingParam("board_id")
                val boardId = boardIdStr.toIntOrNull()
                    ?: return ToolResult("Error: 'board_id' must be an integer, got '$boardIdStr'", "Error: invalid board_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                service.getAvailableSprints(boardId).toAgentToolResult()
            }

            "get_linked_prs" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                ToolValidation.validateNotBlank(issueKey, "key")?.let { return it }
                service.getLinkedPullRequests(issueKey).toAgentToolResult()
            }

            "get_boards" -> {
                val type = params["type"]?.jsonPrimitive?.content
                val nameFilter = params["name_filter"]?.jsonPrimitive?.content
                service.getBoards(type, nameFilter).toAgentToolResult()
            }

            "get_sprint_issues" -> {
                val sprintIdStr = params["sprint_id"]?.jsonPrimitive?.content
                    ?: return missingParam("sprint_id")
                val sprintId = sprintIdStr.toIntOrNull()
                    ?: return ToolResult("Error: 'sprint_id' must be an integer, got '$sprintIdStr'", "Error: invalid sprint_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                service.getSprintIssues(sprintId).toAgentToolResult()
            }

            "get_board_issues" -> {
                val boardIdStr = params["board_id"]?.jsonPrimitive?.content
                    ?: return missingParam("board_id")
                val boardId = boardIdStr.toIntOrNull()
                    ?: return ToolResult("Error: 'board_id' must be an integer, got '$boardIdStr'", "Error: invalid board_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                service.getBoardIssues(boardId).toAgentToolResult()
            }

            "search_issues" -> {
                val text = params["text"]?.jsonPrimitive?.content
                    ?: return missingParam("text")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                service.searchIssues(text, maxResults).toAgentToolResult()
            }

            "get_dev_branches" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                ToolValidation.validateNotBlank(issueKey, "key")?.let { return it }
                service.getDevStatusBranches(issueKey).toAgentToolResult()
            }

            "start_work" -> {
                val issueKey = params["key"]?.jsonPrimitive?.content
                    ?: params["issue_key"]?.jsonPrimitive?.content
                    ?: params["issue_id"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                val branchName = params["branch_name"]?.jsonPrimitive?.content
                    ?: return missingParam("branch_name")
                val sourceBranch = params["source_branch"]?.jsonPrimitive?.content
                    ?: return missingParam("source_branch")
                ToolValidation.validateJiraKey(issueKey)?.let { return it }
                ToolValidation.validateNotBlank(branchName, "branch_name")?.let { return it }
                ToolValidation.validateNotBlank(sourceBranch, "source_branch")?.let { return it }
                service.startWork(issueKey, branchName, sourceBranch).toAgentToolResult()
            }

            "search_tickets" -> {
                val jql = params["jql"]?.jsonPrimitive?.content
                    ?: return missingParam("jql")
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8
                ToolValidation.validateNotBlank(jql, "jql")?.let { return it }
                service.searchTickets(jql, maxResults).toAgentToolResult()
            }

            "download_attachment" -> {
                val key = params["key"]?.jsonPrimitive?.content
                    ?: return missingParam("key")
                val attachmentId = params["attachment_id"]?.jsonPrimitive?.content
                    ?: return missingParam("attachment_id")
                ToolValidation.validateJiraKey(key)?.let { return it }
                service.downloadAttachment(key, attachmentId).toAgentToolResult()
            }

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun missingParam(name: String): ToolResult = ToolResult(
        content = "Error: '$name' parameter required",
        summary = "Error: missing $name",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}

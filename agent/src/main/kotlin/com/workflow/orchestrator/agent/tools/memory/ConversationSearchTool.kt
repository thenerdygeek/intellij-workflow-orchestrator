package com.workflow.orchestrator.agent.tools.memory

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.memory.ConversationRecall
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ConversationSearchTool(private val conversationRecall: ConversationRecall) : AgentTool {
    override val name = "conversation_search"
    override val description = "Search past conversation transcripts across all previous sessions. Use to recall what was discussed, what approaches were tried, or what the user said in earlier sessions."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search query (keywords, case-insensitive)."),
            "roles" to ParameterProperty(type = "string", description = "Optional comma-separated role filter (e.g., 'user,assistant'). Defaults to all roles."),
            "limit" to ParameterProperty(type = "integer", description = "Maximum number of results (default: 20).")
        ),
        required = listOf("query")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'query' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val rolesStr = params["roles"]?.jsonPrimitive?.content
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20

        return try {
            val roles = rolesStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val results = conversationRecall.search(query, roles, limit)

            if (results.isEmpty()) {
                ToolResult(
                    content = "No conversation history found matching: \"$query\"",
                    summary = "No conversation results for '$query'",
                    tokenEstimate = 10
                )
            } else {
                val formatted = results.mapIndexed { i, result ->
                    "${i + 1}. [session: ${result.sessionId}] (${result.role})\n   ${result.content}"
                }.joinToString("\n\n")
                val header = "Found ${results.size} conversation matches for \"$query\":\n\n"
                val content = header + formatted
                ToolResult(
                    content = content,
                    summary = "Found ${results.size} conversation matches for '$query'",
                    tokenEstimate = estimateTokens(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error searching conversations: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}

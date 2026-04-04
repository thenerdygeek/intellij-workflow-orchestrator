package com.workflow.orchestrator.agent.tools.memory

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ArchivalMemorySearchTool(private val archivalMemory: ArchivalMemory) : AgentTool {
    override val name = "archival_memory_search"
    override val description = "Search long-term archival memory by keyword. Tag matches are boosted 3x. Returns most relevant entries. Use to recall past error resolutions, patterns, or decisions."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search query (keywords)."),
            "tags" to ParameterProperty(type = "string", description = "Optional comma-separated tag filter. Only entries with at least one matching tag are returned."),
            "limit" to ParameterProperty(type = "integer", description = "Maximum number of results (default: 10).")
        ),
        required = listOf("query")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'query' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val tagsStr = params["tags"]?.jsonPrimitive?.content
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 10

        return try {
            val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            val results = archivalMemory.search(query, tags, limit)

            if (results.isEmpty()) {
                ToolResult(
                    content = "No archival memory entries found for query: \"$query\"",
                    summary = "No archival results for '$query'",
                    tokenEstimate = 10
                )
            } else {
                val formatted = results.mapIndexed { i, result ->
                    val entry = result.entry
                    "${i + 1}. [${entry.id}] (score: ${result.score}, tags: ${entry.tags.joinToString(", ")})\n   ${entry.content}"
                }.joinToString("\n\n")
                val header = "Found ${results.size} archival memory entries for \"$query\":\n\n"
                val content = header + formatted
                ToolResult(
                    content = content,
                    summary = "Found ${results.size} archival entries for '$query'",
                    tokenEstimate = estimateTokens(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error searching archival memory: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}

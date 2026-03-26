package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.ArchivalMemory
import com.workflow.orchestrator.agent.context.RecallMemory
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Insert knowledge into archival memory with searchable tags.
 */
class ArchivalMemoryInsertTool : AgentTool {
    override val name = "archival_memory_insert"
    override val description = "Store long-term knowledge in archival memory for future retrieval. Unlike core_memory (always in prompt, 4KB limit), archival memory is searched on demand and can hold thousands of entries. Use for: error resolutions, code patterns, API behaviors, decisions and their rationale. IMPORTANT: Include descriptive tags — they are used for search. Include synonyms and related concepts."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "content" to ParameterProperty(type = "string", description = "The knowledge to store. Be specific and actionable."),
            "tags" to ParameterProperty(type = "string", description = "Comma-separated searchable keywords/tags (5-10). Include synonyms, related concepts, error types, file names, tool names. Example: 'edt, freeze, crash, threading, runblocking, deadlock'. These are critical for search."),
            "type" to ParameterProperty(type = "string", description = "Category: 'error_resolution', 'code_pattern', 'decision', 'api_behavior', 'project_convention', 'agent_memory'", enumValues = listOf("error_resolution", "code_pattern", "decision", "api_behavior", "project_convention", "agent_memory"))
        ),
        required = listOf("content", "tags", "type")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val tagsRaw = params["tags"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'tags' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val tags = tagsRaw.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val type = params["type"]?.jsonPrimitive?.content ?: "agent_memory"
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (tags.isEmpty()) {
            return ToolResult("Error: at least 1 tag required for searchability", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val archival = ArchivalMemory.forProject(basePath)
        val id = archival.insert(content, tags, type)

        return ToolResult(
            content = "Stored in archival memory (id=$id, tags=${tags.joinToString(", ")}). Total entries: ${archival.size()}",
            summary = "Archived: ${content.take(60)}...",
            tokenEstimate = 5
        )
    }
}

/**
 * Search archival memory for past knowledge.
 */
class ArchivalMemorySearchTool : AgentTool {
    override val name = "archival_memory_search"
    override val description = "Search long-term archival memory for past knowledge. Finds entries by keyword matching against content and tags. Use when you need to recall: how a bug was fixed, what pattern a project uses, why a decision was made, API quirks discovered in past sessions."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search query — space-separated keywords. More specific queries give better results."),
            "type" to ParameterProperty(type = "string", description = "Optional filter by entry type", enumValues = listOf("error_resolution", "code_pattern", "decision", "api_behavior", "project_convention", "agent_memory")),
            "limit" to ParameterProperty(type = "integer", description = "Max results (default 5)")
        ),
        required = listOf("query")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'query' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val typeFilter = params["type"]?.jsonPrimitive?.content
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5
        val basePath = project.basePath
            ?: return ToolResult("Error: project base path not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val archival = ArchivalMemory.forProject(basePath)
        val results = archival.search(query, topK = limit, typeFilter = typeFilter)

        if (results.isEmpty()) {
            return ToolResult(
                content = "No matches found in archival memory for: \"$query\" (${archival.size()} total entries)",
                summary = "Archival search: 0 results",
                tokenEstimate = 5
            )
        }

        val sb = StringBuilder("Archival memory results for \"$query\" (${results.size} matches):\n\n")
        for ((entry, score) in results) {
            sb.appendLine("--- [${entry.id}] (score: ${"%.1f".format(score)}, type: ${entry.type}) ---")
            sb.appendLine(entry.content)
            sb.appendLine("Tags: ${entry.tags.joinToString(", ")}")
            sb.appendLine()
        }

        return ToolResult(
            content = sb.toString().trimEnd(),
            summary = "Archival search: ${results.size} results for \"$query\"",
            tokenEstimate = sb.length / 4
        )
    }
}

/**
 * Search past conversation sessions for context.
 */
class ConversationSearchTool : AgentTool {
    override val name = "conversation_search"
    override val description = "Search past conversation sessions for what was discussed, decided, or done. Useful when you need context from a previous session: 'what did we do about X?', 'when did we last change Y?', 'what was the error with Z?'."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search query — keywords to find in past conversations"),
            "limit" to ParameterProperty(type = "integer", description = "Max sessions to return (default 5)")
        ),
        required = listOf("query")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'query' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val limit = params["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

        val recall = RecallMemory()
        val results = recall.search(query, projectPath = project.basePath, maxSessions = limit)

        if (results.isEmpty()) {
            return ToolResult(
                content = "No past conversations found matching: \"$query\"",
                summary = "Conversation search: 0 results",
                tokenEstimate = 5
            )
        }

        val sb = StringBuilder("Past conversations matching \"$query\" (${results.size} sessions):\n\n")
        for (result in results) {
            sb.appendLine("=== Session: ${result.sessionTitle} (${result.sessionId}) ===")
            for (msg in result.matchingMessages) {
                sb.appendLine("[${msg.role}]: ${msg.contentPreview}")
            }
            sb.appendLine()
        }

        return ToolResult(
            content = sb.toString().trimEnd(),
            summary = "Conversation search: ${results.size} sessions for \"$query\"",
            tokenEstimate = sb.length / 4
        )
    }
}

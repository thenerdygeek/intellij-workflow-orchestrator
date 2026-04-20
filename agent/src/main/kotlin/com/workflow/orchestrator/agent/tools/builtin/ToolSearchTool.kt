package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Claude Code-style tool search: lets the LLM discover and load deferred tools
 * that aren't in the initial tool set.
 *
 * The LLM calls this when it needs a capability not covered by core tools.
 * Matched tools are activated and become available in subsequent API calls.
 *
 * Query forms:
 * - "jira tickets" — keyword search, returns best matches
 * - "select:jira,sonar" — load specific tools by exact name
 */
class ToolSearchTool(private val registry: ToolRegistry) : AgentTool {

    override val name = "tool_search"

    override val description = """Search for and load specialized tools that aren't in your current toolset.

Use this when you need a capability not covered by your current tools.
For example, if you need to work with Jira tickets, search for "jira".
If you need to debug, search for "debug".

The search returns tool names and descriptions. Once loaded, the tools
become available for you to call in subsequent responses.

Query forms:
- "jira" — keyword search, returns best matches
- "select:jira,sonar" — load specific tools by exact name""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(
                type = "string",
                description = "Search query — keyword(s) to find relevant tools, or 'select:name1,name2' to load specific tools by name"
            ),
            "max_results" to ParameterProperty(
                type = "integer",
                description = "Maximum tools to return. Default: 5"
            )
        ),
        required = listOf("query")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: missing required 'query' parameter.",
                summary = "Missing query parameter",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val maxResults = params["max_results"]?.jsonPrimitive?.intOrNull ?: 5

        val matches = if (query.startsWith("select:")) {
            // Exact name match — load specific tools
            val names = query.removePrefix("select:").split(",").map { it.trim() }
            names.mapNotNull { toolName ->
                registry.activateDeferred(toolName)
            }
        } else {
            // Keyword search — find and activate matching tools
            val results = registry.searchDeferred(query, maxResults)
            results.forEach { registry.activateDeferred(it.name) }
            results
        }

        if (matches.isEmpty()) {
            val catalog = registry.getDeferredCatalog()
            val catalogText = if (catalog.isNotEmpty()) {
                "\n\nAvailable deferred tools:\n" +
                    catalog.joinToString("\n") { "- ${it.first}: ${it.second}" }
            } else {
                "\n\nNo deferred tools are currently registered."
            }

            return ToolResult(
                content = "No tools found matching '$query'.$catalogText",
                summary = "No tools found for '$query'",
                tokenEstimate = estimateTokens(catalogText)
            )
        }

        // Build detailed descriptions of loaded tools
        val loaded = matches.joinToString("\n\n") { tool ->
            buildString {
                appendLine("## ${tool.name}")
                appendLine(tool.description.lines().take(5).joinToString("\n"))
                append("Parameters: ${tool.parameters.properties.keys.joinToString(", ")}")
            }
        }

        val relatedHint = getRelatedToolsHint(matches.map { it.name })
        val content = buildString {
            append("Loaded ${matches.size} tool(s):\n\n$loaded\n\nThese tools are now available for you to call.")
            if (relatedHint.isNotEmpty()) {
                append("\n\nRelated tools you may also find useful (load via tool_search): $relatedHint")
            }
        }

        return ToolResult(
            content = content,
            summary = "Loaded tools: ${matches.joinToString(", ") { it.name }}",
            tokenEstimate = estimateTokens(content)
        )
    }

    /**
     * Suggest related tools based on what was just loaded.
     * Helps the LLM discover complementary tools it might not know about.
     */
    internal fun getRelatedToolsHint(loadedNames: List<String>): String {
        val related = mutableSetOf<String>()
        for (name in loadedNames) {
            when {
                name == "endpoints" -> related.addAll(listOf("spring", "build", "coverage", "db_schema"))
                name.startsWith("spring") -> related.addAll(listOf("endpoints", "build", "coverage", "db_schema"))
                name.startsWith("django") -> related.addAll(listOf("build", "db_schema", "db_query"))
                name.startsWith("fastapi") -> related.addAll(listOf("build", "db_schema"))
                name.startsWith("flask") -> related.addAll(listOf("build", "db_schema"))
                name == "build" -> related.addAll(listOf("coverage", "runtime_exec"))
                name.startsWith("debug") -> related.addAll(listOf("diagnostics", "runtime_exec"))
                name == "coverage" -> related.add("runtime_exec")
                name.startsWith("bitbucket") -> related.addAll(listOf("git"))
                name.startsWith("jira") -> related.addAll(listOf("git"))
                name.startsWith("sonar") -> related.addAll(listOf("diagnostics", "coverage"))
            }
        }
        // Don't suggest tools that were just loaded
        related.removeAll(loadedNames.toSet())
        return related.take(3).joinToString(", ")
    }
}

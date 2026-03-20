package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.settings.ToolPreferences
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolCategoryRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Meta-tool that lets the LLM activate additional tool categories on demand.
 *
 * The hybrid approach: DynamicToolSelector sends core + keyword-matched tools
 * with full definitions. This tool is always included so the LLM can request
 * categories that weren't matched by keywords. On the next iteration, those
 * tools appear with full parameter definitions.
 *
 * Example: User asks "fix the NPE" (no build keywords). LLM reads code, edits,
 * then calls request_tools(category="ide", reason="need to run tests after fix").
 * Next iteration: run_tests and compile_module are available.
 */
class RequestToolsTool : AgentTool {
    override val name = "request_tools"
    override val description: String
        get() {
            val cats = ToolCategoryRegistry.getActivatableCategories()
            val catList = cats.joinToString("; ") { "'${it.id}': ${it.description}" }
            return "Request additional tools. Available categories: $catList. Call when you need tools not currently available."
        }
    override val parameters = FunctionParameters(
        properties = mapOf(
            "category" to ParameterProperty(
                type = "string",
                description = "Category to activate: ${ToolCategoryRegistry.getActivatableCategories().joinToString(", ") { it.id }}"
            ),
            "reason" to ParameterProperty(
                type = "string",
                description = "Brief reason why you need these tools (for logging)"
            )
        ),
        required = listOf("category")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val categoryId = params["category"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'category' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val reason = params["reason"]?.jsonPrimitive?.content ?: ""

        val toolNames = ToolCategoryRegistry.getToolsInCategory(categoryId)
        if (toolNames.isEmpty()) {
            val available = ToolCategoryRegistry.getActivatableCategories().joinToString(", ") { it.id }
            return ToolResult(
                "Unknown category '$categoryId'. Available categories: $available",
                "Unknown category", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // Filter by user preferences
        val enabledTools = try {
            val prefs = ToolPreferences.getInstance(project)
            toolNames.filter { prefs.isToolEnabled(it) }
        } catch (_: Exception) { toolNames }

        if (enabledTools.isEmpty()) {
            return ToolResult(
                "All tools in '$categoryId' are disabled by user preferences. Enable them in the Tools panel.",
                "All disabled", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // Queue tools for activation on next iteration
        try {
            val agentService = AgentService.getInstance(project)
            agentService.pendingToolActivations.addAll(enabledTools)
        } catch (_: Exception) {
            return ToolResult("Error: agent service not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val category = ToolCategoryRegistry.getActivatableCategories().find { it.id == categoryId }
        return ToolResult(
            "Activated ${enabledTools.size} tools from '${category?.displayName ?: categoryId}': ${enabledTools.joinToString(", ")}. These are now available for your next action.${if (reason.isNotBlank()) " Reason: $reason" else ""}",
            "Activated $categoryId (${enabledTools.size} tools)",
            10
        )
    }
}

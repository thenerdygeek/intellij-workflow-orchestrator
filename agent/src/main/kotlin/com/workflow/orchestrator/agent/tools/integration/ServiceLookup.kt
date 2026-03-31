package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.services.*

/**
 * Centralized service lookup for agent integration tools.
 * Returns null if the service is not registered (module not loaded or not configured).
 */
object ServiceLookup {

    fun jira(project: Project): JiraService? = try {
        project.getService(JiraService::class.java)
    } catch (_: Exception) { null }

    fun bamboo(project: Project): BambooService? = try {
        project.getService(BambooService::class.java)
    } catch (_: Exception) { null }

    fun sonar(project: Project): SonarService? = try {
        project.getService(SonarService::class.java)
    } catch (_: Exception) { null }

    fun bitbucket(project: Project): BitbucketService? = try {
        project.getService(BitbucketService::class.java)
    } catch (_: Exception) { null }

    fun notConfigured(serviceName: String): ToolResult = ToolResult(
        content = "$serviceName not configured. Set up connection in Settings > Workflow Orchestrator > Connections.",
        summary = "$serviceName not available",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}

/**
 * Convert a core ToolResult<T> to an agent ToolResult.
 *
 * Strategy: The `summary` field is a one-liner for logs/notifications (e.g., "3 comment(s) on PROJ-123").
 * The `data` field contains the actual structured content the LLM needs to see.
 *
 * - If `data` is a non-empty List, each item is rendered via toString()
 * - If `data` is Unit or null, only the summary is used
 * - Otherwise, data.toString() is appended after the summary
 *
 * This ensures the LLM sees the actual content (comment text, issue details, etc.)
 * rather than just a count.
 */
fun <T> com.workflow.orchestrator.core.services.ToolResult<T>.toAgentToolResult(): ToolResult {
    val content = buildString {
        append(summary)
        // Append structured data content when available
        val dataVal = data
        when {
            dataVal == null || dataVal == Unit -> { /* summary is sufficient */ }
            dataVal is List<*> && dataVal.isNotEmpty() -> {
                append("\n\n")
                dataVal.forEachIndexed { index, item ->
                    if (index > 0) append("\n")
                    append(item.toString())
                }
            }
            dataVal is List<*> -> { /* empty list, summary already says "0 items" */ }
            else -> {
                val dataStr = dataVal.toString()
                // Avoid duplicating if data.toString() equals the summary
                if (dataStr != summary && dataStr.isNotBlank() &&
                    !dataStr.startsWith(dataVal::class.java.name)) {
                    append("\n\n")
                    append(dataStr)
                }
            }
        }
        if (hint != null) append("\nHint: $hint")
    }
    return ToolResult(
        content = content,
        summary = summary.lines().firstOrNull() ?: "",
        tokenEstimate = TokenEstimator.estimate(content),
        isError = isError
    )
}

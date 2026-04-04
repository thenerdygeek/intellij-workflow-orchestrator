package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.ai.TokenEstimator
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

/** Max chars for raw string data (build logs, diffs, file content). */
private const val MAX_DATA_STRING_CHARS = 10_000

/** Max list items to render before truncating. */
private const val MAX_LIST_ITEMS = 50

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
            dataVal is String -> {
                // Raw string data (build logs, diffs, file content) — cap at 10K chars
                if (dataVal.length > MAX_DATA_STRING_CHARS) {
                    append("\n\n")
                    append(dataVal.take(MAX_DATA_STRING_CHARS))
                    append("\n\n[TRUNCATED — ${dataVal.length} total chars, showing first $MAX_DATA_STRING_CHARS]")
                } else if (dataVal != summary && dataVal.isNotBlank()) {
                    append("\n\n")
                    append(dataVal)
                }
            }
            dataVal is List<*> && dataVal.isNotEmpty() -> {
                append("\n\n")
                // Cap list rendering to avoid context overflow
                val items = if (dataVal.size > MAX_LIST_ITEMS) dataVal.take(MAX_LIST_ITEMS) else dataVal
                items.forEachIndexed { index, item ->
                    if (index > 0) append("\n")
                    append(item.toString())
                }
                if (dataVal.size > MAX_LIST_ITEMS) {
                    append("\n... and ${dataVal.size - MAX_LIST_ITEMS} more (${dataVal.size} total)")
                }
            }
            dataVal is List<*> -> { /* empty list, summary already says "0 items" */ }
            else -> {
                val dataStr = dataVal.toString()
                if (dataStr != summary && dataStr.isNotBlank() &&
                    !dataStr.startsWith(dataVal::class.java.name)) {
                    append("\n\n")
                    // Cap non-list structured data at 10K
                    if (dataStr.length > MAX_DATA_STRING_CHARS) {
                        append(dataStr.take(MAX_DATA_STRING_CHARS))
                        append("\n[TRUNCATED]")
                    } else {
                        append(dataStr)
                    }
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

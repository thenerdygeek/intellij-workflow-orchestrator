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
 * Uses the summary (LLM-optimized text) as the content.
 */
fun <T> com.workflow.orchestrator.core.services.ToolResult<T>.toAgentToolResult(): ToolResult {
    val content = buildString {
        append(summary)
        if (hint != null) append("\nHint: $hint")
    }
    return ToolResult(
        content = content,
        summary = summary.lines().firstOrNull() ?: "",
        tokenEstimate = TokenEstimator.estimate(content),
        isError = isError
    )
}

package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionDefinition
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolDefinition
import kotlinx.serialization.json.JsonObject

/** Worker type for tool access control. Kept for compatibility with existing tools. */
enum class WorkerType {
    ORCHESTRATOR, ANALYZER, CODER, REVIEWER, TOOLER
}

/** Simple token estimation: bytes / 4 (Codex CLI pattern, ~80% accurate). */
fun estimateTokens(text: String): Int = (text.toByteArray().size + 3) / 4

/**
 * Middle-truncate content keeping first 60% and last 40%.
 */
fun truncateOutput(content: String, maxChars: Int = 50_000): String {
    if (content.length <= maxChars) return content
    val headChars = (maxChars * 0.6).toInt()
    val tailChars = maxChars - headChars - 200
    val omitted = content.length - headChars - tailChars
    return content.take(headChars) +
        "\n\n[... $omitted characters omitted ...]\n\n" +
        content.takeLast(tailChars)
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: FunctionParameters
    val allowedWorkers: Set<WorkerType>

    suspend fun execute(params: JsonObject, project: Project): ToolResult

    fun toToolDefinition(): ToolDefinition = ToolDefinition(
        function = FunctionDefinition(
            name = name,
            description = description,
            parameters = parameters
        )
    )
}

data class ToolResult(
    val content: String,
    val summary: String,
    val tokenEstimate: Int,
    val artifacts: List<String> = emptyList(),
    val isError: Boolean = false,
    val isCompletion: Boolean = false,
    val verifyCommand: String? = null
) {
    companion object {
        const val ERROR_TOKEN_ESTIMATE = 5
    }
}

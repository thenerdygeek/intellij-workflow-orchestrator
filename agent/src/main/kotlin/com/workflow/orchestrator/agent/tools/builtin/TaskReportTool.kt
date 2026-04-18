package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sub-agent completion tool that replaces [AttemptCompletionTool] for sub-agents.
 *
 * Unlike [AttemptCompletionTool] (which writes a short UI-facing summary card for the
 * orchestrator), this tool forces the sub-agent to produce a comprehensive report that
 * flows directly into the parent LLM's tool result. The parent cannot see the sub-agent's
 * conversation, streamed text, or internal tool calls — only the fields written here.
 *
 * Returning [ToolResult.completion] triggers loop termination via [ToolResultType.Completion],
 * the same type-based exit path used by [AttemptCompletionTool].
 *
 * [allowedWorkers] deliberately excludes [WorkerType.ORCHESTRATOR] so the orchestrator
 * continues to use [AttemptCompletionTool] (the user-facing signal). The [ToolRegistry]
 * worker-type filter enforces this at schema time.
 */
class TaskReportTool : AgentTool {

    override val name = "task_report"

    override val description = """
        Signal task completion and report findings to the parent agent. This is how a sub-agent
        hands back its result. Everything you write here flows DIRECTLY into the parent LLM's
        tool result — the parent cannot see your conversation, your tool calls, or your streamed
        text. Put your comprehensive findings in these fields, not a short summary.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "summary" to ParameterProperty(
                type = "string",
                description = "One paragraph: what was done, the overall conclusion, and whether the task succeeded."
            ),
            "findings" to ParameterProperty(
                type = "string",
                description = "Detailed findings with analysis. Markdown allowed. Include inline code snippets (with file:line) where they support the analysis."
            ),
            "files" to ParameterProperty(
                type = "string",
                description = "Newline-separated list of file paths examined or modified. One path per line. The parent uses this to know what has already been read."
            ),
            "next_steps" to ParameterProperty(
                type = "string",
                description = "What the parent agent should do next based on these findings. Be concrete and actionable."
            ),
            "issues" to ParameterProperty(
                type = "string",
                description = "Blockers, errors, or unresolved questions encountered. Empty if none."
            )
        ),
        required = listOf("summary")
    )

    // Notably excludes ORCHESTRATOR — orchestrator uses attempt_completion.
    // ToolRegistry worker-type filter gates this at schema time.
    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER,
        WorkerType.ANALYZER, WorkerType.TOOLER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val summary = params["summary"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing required parameter: summary", "task_report failed: missing summary")

        val findings = params["findings"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val files = params["files"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val nextSteps = params["next_steps"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val issues = params["issues"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val content = buildString {
            appendLine("## Summary")
            appendLine(summary)
            if (findings != null) {
                appendLine()
                appendLine("## Findings")
                appendLine(findings)
            }
            if (files != null) {
                appendLine()
                appendLine("## Files")
                appendLine(files)
            }
            if (nextSteps != null) {
                appendLine()
                appendLine("## Next Steps")
                appendLine(nextSteps)
            }
            if (issues != null) {
                appendLine()
                appendLine("## Issues")
                appendLine(issues)
            }
        }.trimEnd()

        return ToolResult.completion(
            content = content,
            summary = "Task report: ${summary.take(200)}",
            tokenEstimate = content.length / 4
        )
    }
}

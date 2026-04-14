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
 * Session handoff tool -- faithful port of Cline's new_task.
 *
 * When context is exhausted (compaction can't save enough), the LLM calls
 * this tool to hand off to a fresh session with a structured context summary.
 * The summary preserves essential state so the new session can continue
 * without losing critical information.
 *
 * From Cline (src/core/prompts/system-prompt/tools/new_task.ts):
 * "Request to create a new task with preloaded context covering the
 * conversation with the user up to this point and key information for
 * continuing with the new task."
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/prompts/system-prompt/tools/new_task.ts">Cline source</a>
 */
class NewTaskTool : AgentTool {

    override val name = "new_task"

    // Faithfully ported from Cline's new_task tool description
    override val description = "Request to create a new task with preloaded context covering the conversation " +
        "with the user up to this point and key information for continuing with the new task. With this tool, " +
        "you will create a detailed summary of the conversation so far, paying close attention to the user's " +
        "explicit requests and your previous actions, with a focus on the most relevant information required " +
        "for the new task.\n" +
        "Among other important areas of focus, this summary should be thorough in capturing technical details, " +
        "code patterns, and architectural decisions that would be essential for continuing with the new task. " +
        "The user will be presented with a preview of your generated context and can choose to create a new " +
        "task or keep chatting in the current conversation. The user may choose to start a new task at any point."

    // Faithfully ported from Cline's parameter description
    override val parameters = FunctionParameters(
        properties = mapOf(
            "context" to ParameterProperty(
                type = "string",
                description = "The context to preload the new task with. If applicable based on the " +
                    "current task, this should include:\n" +
                    "  1. Current Work: Describe in detail what was being worked on prior to this request " +
                    "to create a new task. Pay special attention to the more recent messages / conversation.\n" +
                    "  2. Key Technical Concepts: List all important technical concepts, technologies, " +
                    "coding conventions, and frameworks discussed, which might be relevant for the new task.\n" +
                    "  3. Relevant Files and Code: If applicable, enumerate specific files and code sections " +
                    "examined, modified, or created for the task continuation. Pay special attention to the " +
                    "most recent messages and changes.\n" +
                    "  4. Problem Solving: Document problems solved thus far and any ongoing troubleshooting " +
                    "efforts.\n" +
                    "  5. Pending Tasks and Next Steps: Outline all pending tasks that you have explicitly " +
                    "been asked to work on, as well as list the next steps you will take for all outstanding " +
                    "work, if applicable. Include code snippets where they add clarity. For any next steps, " +
                    "include direct quotes from the most recent conversation showing exactly what task you " +
                    "were working on and where you left off. This should be verbatim to ensure there's no " +
                    "information loss in context between tasks. It's important to be detailed here."
            )
        ),
        required = listOf("context")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val context = params["context"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: context",
                summary = "new_task failed: missing context",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (context.isBlank()) {
            return ToolResult(
                content = "Error: context parameter must not be empty. Provide a detailed summary " +
                    "including Current Work, Key Technical Concepts, Relevant Files, Problem Solving, " +
                    "and Pending Tasks.",
                summary = "new_task failed: empty context",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return ToolResult.sessionHandoff(
            content = context,
            summary = "Session handoff: context preserved (${context.length} chars)",
            tokenEstimate = context.length / 4,
            context = context
        )
    }
}

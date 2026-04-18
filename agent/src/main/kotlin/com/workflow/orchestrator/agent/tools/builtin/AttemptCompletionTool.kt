package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.CompletionData
import com.workflow.orchestrator.agent.tools.CompletionKind
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class AttemptCompletionTool : AgentTool {

    override val name = "attempt_completion"

    override val description = """
        Call this tool to stop the current task. This is the ONLY way to end a task — text-only responses without a tool call are NOT valid exits.

        Choose the kind that describes what kind of completion this is:
        - "done": Work is complete. No user action needed. Example result: "Refactored AuthService to use constructor injection. All 14 tests pass."
        - "review": The output needs the user to inspect, validate, or decide something before they can be confident. Put the verify-by instruction in verify_how. Example result: "Added the feature flag. Please check the admin panel to confirm the toggle is visible."
        - "heads_up": You discovered something important that the user should know — a hidden risk, a scope gap, a notable finding — even though the immediate task is complete. Put the finding in discovery. Example result: "Completed the migration. Discovery: the old schema still has 3 orphaned tables that were not in the migration spec."

        Use ask_followup_question instead if you are blocked and user input can unblock you.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "kind" to ParameterProperty(
                type = "string",
                description = "Classification of this completion. Must be 'done', 'review', or 'heads_up'. See tool description for criteria.",
                enumValues = listOf("done", "review", "heads_up")
            ),
            "result" to ParameterProperty(
                type = "string",
                description = "Short summary card shown to the user. Must be concise — your detailed explanation should be in the streamed text BEFORE this tool call, not here."
            ),
            "verify_how" to ParameterProperty(
                type = "string",
                description = "Optional: a CLI command, URL, or instruction the user can follow to verify the result. Valid on all kinds. When kind=review, this is the primary CTA and should clearly describe what to check."
            ),
            "discovery" to ParameterProperty(
                type = "string",
                description = "Required when kind=heads_up: the surprising finding, hidden risk, or scope gap the user needs to know about. Must be omitted or null for done and review."
            )
        ),
        required = listOf("kind", "result")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val kindStr = params["kind"]?.jsonPrimitive?.content
            ?: return ToolResult.error(
                message = "Missing required parameter: kind",
                summary = "attempt_completion failed: missing kind"
            )

        val kind = when (kindStr) {
            "done" -> CompletionKind.DONE
            "review" -> CompletionKind.REVIEW
            "heads_up" -> CompletionKind.HEADS_UP
            else -> return ToolResult.error(
                message = "Invalid value for 'kind': '$kindStr'. Must be one of: done, review, heads_up",
                summary = "attempt_completion failed: invalid kind '$kindStr'"
            )
        }

        val result = params["result"]?.jsonPrimitive?.content
            ?: return ToolResult.error(
                message = "Missing required parameter: result",
                summary = "attempt_completion failed: missing result"
            )

        val verifyHow = params["verify_how"]?.jsonPrimitive?.content
        val discovery = params["discovery"]?.jsonPrimitive?.content

        if (kind == CompletionKind.HEADS_UP && discovery.isNullOrBlank()) {
            return ToolResult.error(
                message = "kind=heads_up requires a non-empty 'discovery' field describing the finding",
                summary = "attempt_completion failed: heads_up requires discovery"
            )
        }

        return ToolResult.completion(
            content = result,
            summary = "Task $kindStr: ${result.take(200)}",
            tokenEstimate = result.length / 4,
            completionData = CompletionData(kind = kind, result = result, verifyHow = verifyHow, discovery = discovery)
        )
    }
}

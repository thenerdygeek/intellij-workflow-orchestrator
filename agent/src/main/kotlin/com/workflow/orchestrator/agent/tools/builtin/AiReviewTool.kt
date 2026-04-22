package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.prreview.AnchorSide
import com.workflow.orchestrator.core.prreview.FindingSeverity
import com.workflow.orchestrator.core.prreview.PrReviewFinding
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStore
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStoreImpl
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Local PR-review findings store for the code-reviewer persona.
 *
 * Writes only to local disk — no Bitbucket API calls. Findings are staged locally and
 * pushed to Bitbucket by the user from the AI Review sub-tab.
 *
 * 3 actions: add_finding, list_findings, clear_findings
 */
class AiReviewTool(
    private val project: Project,
    private val storeProvider: () -> PrReviewFindingsStore = { project.service<PrReviewFindingsStoreImpl>() },
) : AgentTool {

    override val name: String = "ai_review"

    override val description: String = """
Local PR-review findings store for the code-reviewer persona during a PR review session.
Actions: add_finding, list_findings, clear_findings.
Writes only to local disk — no Bitbucket API calls.
Called by the code-reviewer persona to stage findings BEFORE pushing to Bitbucket (push is the user's action from the AI Review sub-tab).

Actions:
- add_finding(pr_id, session_id, severity, message, [file, line_start, line_end, anchor_side, suggestion]) → Add a review finding
- list_findings(pr_id, [session_id, include_archived]) → List staged findings
- clear_findings(pr_id, session_id) → Remove all findings for a session
""".trimIndent()

    override val parameters: FunctionParameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("add_finding", "list_findings", "clear_findings")
            ),
            "pr_id" to ParameterProperty(
                type = "string",
                description = "Pull request ID — required for all actions"
            ),
            "session_id" to ParameterProperty(
                type = "string",
                description = "Agent session ID — required for add_finding and clear_findings; optional filter for list_findings"
            ),
            "severity" to ParameterProperty(
                type = "string",
                description = "Finding severity: NORMAL or BLOCKER — required for add_finding",
                enumValues = listOf("NORMAL", "BLOCKER")
            ),
            "message" to ParameterProperty(
                type = "string",
                description = "Review comment text — required for add_finding"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "File path for inline finding — optional for add_finding"
            ),
            "line_start" to ParameterProperty(
                type = "string",
                description = "Starting line number (integer) — optional for add_finding"
            ),
            "line_end" to ParameterProperty(
                type = "string",
                description = "Ending line number (integer) — optional for add_finding"
            ),
            "anchor_side" to ParameterProperty(
                type = "string",
                description = "Diff side: ADDED, REMOVED, or CONTEXT — optional for add_finding",
                enumValues = listOf("ADDED", "REMOVED", "CONTEXT")
            ),
            "suggestion" to ParameterProperty(
                type = "string",
                description = "Suggested replacement code — optional for add_finding. " +
                    "Rendered as a Bitbucket suggestion block on push."
            ),
            "include_archived" to ParameterProperty(
                type = "string",
                description = "Include archived findings: true/false — optional for list_findings (default false)"
            ),
        ),
        required = listOf("action")
    )

    override val allowedWorkers: Set<WorkerType> = setOf(
        WorkerType.REVIEWER,
        WorkerType.ORCHESTRATOR,
        WorkerType.ANALYZER,
    )

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Error: 'action' parameter required", "ai_review: missing action")

        return when (action) {
            "add_finding" -> addFinding(params)
            "list_findings" -> listFindings(params)
            "clear_findings" -> clearFindings(params)
            else -> ToolResult.error(
                "Error: unknown action '$action'. Valid actions: add_finding, list_findings, clear_findings",
                "ai_review: unknown action '$action'"
            )
        }
    }

    private suspend fun addFinding(params: JsonObject): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'pr_id' is required for add_finding", "ai_review: missing pr_id")

        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'session_id' is required for add_finding", "ai_review: missing session_id")

        val severityStr = params["severity"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'severity' is required for add_finding (NORMAL or BLOCKER)", "ai_review: missing severity")

        val severity = runCatching { FindingSeverity.valueOf(severityStr.uppercase()) }.getOrNull()
            ?: return ToolResult.error(
                "Error: invalid severity '$severityStr'. Must be NORMAL or BLOCKER",
                "ai_review: invalid severity '$severityStr'"
            )

        val baseMessage = params["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'message' is required for add_finding", "ai_review: missing message")

        val suggestion = params["suggestion"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val message = if (suggestion != null) {
            "$baseMessage\n\n```suggestion\n$suggestion\n```"
        } else {
            baseMessage
        }

        val file = params["file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val lineStart = params["line_start"]?.jsonPrimitive?.intOrNull
            ?: params["line_start"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        val lineEnd = params["line_end"]?.jsonPrimitive?.intOrNull
            ?: params["line_end"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        val anchorSideStr = params["anchor_side"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val anchorSide = anchorSideStr?.let {
            runCatching { AnchorSide.valueOf(it.uppercase()) }.getOrNull()
                ?: return ToolResult.error(
                    "Error: invalid anchor_side '$it'. Must be ADDED, REMOVED, or CONTEXT",
                    "ai_review: invalid anchor_side '$it'"
                )
        }

        val finding = PrReviewFinding(
            id = UUID.randomUUID().toString(),
            prId = prId,
            sessionId = sessionId,
            file = file,
            lineStart = lineStart,
            lineEnd = lineEnd,
            anchorSide = anchorSide,
            severity = severity,
            message = message,
            createdAt = System.currentTimeMillis(),
        )

        val result = storeProvider().add(finding)
        return if (result.isError) {
            ToolResult.error(
                "Error: failed to add finding — ${result.summary}",
                "ai_review: add_finding failed"
            )
        } else {
            val encoded = json.encodeToString(result.data)
            ToolResult(
                content = "Finding added.\n$encoded",
                summary = "Added finding ${result.data.id} (${severity.name})",
                tokenEstimate = encoded.length / 4,
            )
        }
    }

    private suspend fun listFindings(params: JsonObject): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'pr_id' is required for list_findings", "ai_review: missing pr_id")

        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val includeArchived = params["include_archived"]?.jsonPrimitive?.booleanOrNull
            ?: params["include_archived"]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true)
            ?: false

        val result = storeProvider().list(prId, sessionId, includeArchived)
        return if (result.isError) {
            ToolResult.error(
                "Error: failed to list findings — ${result.summary}",
                "ai_review: list_findings failed"
            )
        } else {
            val encoded = json.encodeToString(result.data)
            ToolResult(
                content = encoded,
                summary = result.summary,
                tokenEstimate = encoded.length / 4,
            )
        }
    }

    private suspend fun clearFindings(params: JsonObject): ToolResult {
        val prId = params["pr_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'pr_id' is required for clear_findings", "ai_review: missing pr_id")

        val sessionId = params["session_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Error: 'session_id' is required for clear_findings", "ai_review: missing session_id")

        val result = storeProvider().clear(prId, sessionId)
        return if (result.isError) {
            ToolResult.error(
                "Error: failed to clear findings — ${result.summary}",
                "ai_review: clear_findings failed"
            )
        } else {
            ToolResult(
                content = "Findings cleared for PR $prId / session $sessionId.",
                summary = result.summary,
                tokenEstimate = 5,
            )
        }
    }
}

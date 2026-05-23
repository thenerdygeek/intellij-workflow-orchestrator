package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.delegation.DelegationException
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.delegation.DelegationMessage
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `delegation_send` — request work from an agent in another running IntelliJ
 * instance that holds a different repo open.
 *
 * Supports two dispatch modes:
 * - **Fresh send** (no `handle`): the picker opens, the user picks the target
 *   IDE, a Connect handshake is sent, and the handle is returned to the LLM.
 *   The actual work result arrives later as a system nudge injected via
 *   [AgentService.enqueueNudgeForSession] when the remote agent finishes.
 * - **Continuation** (`handle` set, Plan 4): skips picker + Accept; sends a
 *   [com.workflow.orchestrator.core.delegation.DelegationMessage.UserTurn] over
 *   the existing channel via [DelegationOutboundService.sendContinuation].
 *   Returns immediately; results still arrive as a nudge.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §4.1, §5.2, §6.5.
 */
class DelegationSendTool : AgentTool {

    override val name = "delegation_send"

    override val description = """
        Delegate a task to an agent running in a DIFFERENT IntelliJ instance that holds a
        different repository open.

        When to use:
        - The current task requires changes across multiple repos (e.g. backend + frontend,
          library + consumer) that are open in separate IDE windows.
        - You need a remote agent to run tests, inspect code, or make commits in another repo.

        How it works:
        1. A picker dialog opens for the user to choose the target IDE / repo.
        2. A Connect handshake is sent; the remote IDE shows an Accept dialog.
        3. On Accept, this tool returns immediately with a handle and "running" status.
        4. When the remote agent finishes (COMPLETED / CANCELED / FAILED), a nudge
           message is automatically injected into this session so you can see the result.
        5. You do NOT need to poll — just continue working; the result will arrive.

        Args:
          request        (required) The full briefing for the remote agent: what to do,
                         relevant context, expected deliverables.
          suggested_repo (optional) Name hint pre-selects an entry in the picker. Helpful
                         when you know which repo is needed (e.g. "frontend", "auth-service").
          handle         (optional) Existing channel handle to reuse. When set, skips the
                         picker and Accept dialog; sends the request as a new user turn to
                         the existing Agent-B session. Use to follow up on a previous delegation.

        Returns on success:
          JSON with handle id, status "running", and the repo name the user picked.
        Returns on failure:
          Distinct error codes: DelegationUserCanceledPicker, DelegationTargetNotReachable,
          DelegationLimitReached, DelegationRejected.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "request" to ParameterProperty(
                type = "string",
                description = "Full briefing for the remote agent: what to do, relevant context, expected deliverables."
            ),
            "suggested_repo" to ParameterProperty(
                type = "string",
                description = "Optional repo name hint to pre-select in the picker (e.g. \"frontend\")."
            ),
            "handle" to ParameterProperty(
                type = "string",
                description = "Existing channel handle to reuse. When set, skips the picker and Accept dialog; " +
                    "sends the request as a new user turn to the existing Agent-B session. " +
                    "Use to follow up on a previous delegation. Plan 4 §5.2."
            ),
        ),
        required = listOf("request"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.ANALYZER,
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // F3: runtime gate — if outbound delegation was disabled after the tool was registered,
        // return a typed error instead of relying on tool unregistration alone. The LLM
        // would otherwise receive a confusing "Unknown tool" error for the mid-session race.
        if (!PluginSettings.getInstance(project).state.enableOutboundCrossIdeDelegation) {
            return ToolResult.error(
                "DelegationOutboundDisabled: cross-IDE delegation is currently disabled in settings " +
                    "(Tools → Workflow Orchestrator → Agent → Enable outbound cross-IDE delegation)"
            )
        }

        val request = params["request"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation_send: 'request' is required")

        val suggestedRepo = params["suggested_repo"]?.jsonPrimitive?.content

        val agentService = project.getService(AgentService::class.java)
            ?: return ToolResult.error("delegation_send: AgentService unavailable")

        val outboundService = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation_send: DelegationOutboundService unavailable")

        // Capture the delegator session ID at call time; the closure must close over a
        // non-null copy because the active session may change by the time onResult fires.
        val delegatorSessionId = agentService.currentSessionState()?.sessionId
            ?: return ToolResult.error("delegation_send: no active session — cannot determine delegator session ID")

        // Plan 4: continue_with branch. Skip picker + Accept; send UserTurn over existing channel.
        val handleId = params["handle"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (handleId != null) {
            return try {
                val handle = outboundService.sendContinuation(
                    handleId = handleId,
                    request = request,
                    delegatorSessionId = delegatorSessionId,
                )
                val shortId = handle.id.take(8)
                val content = buildString {
                    appendLine("""{"handle":"${handle.id}","status":"running","repo":"${handle.targetRepoName}"}""")
                    appendLine()
                    appendLine(
                        "Continuation sent to ${handle.targetRepoName} (handle $shortId). " +
                            "Agent-B will process the new user turn; results will arrive as a nudge."
                    )
                }.trimEnd()
                ToolResult(
                    content = content,
                    summary = "Continuation sent to ${handle.targetRepoName} ($shortId) — awaiting result",
                    tokenEstimate = 30,
                )
            } catch (e: DelegationException.Expired) {
                ToolResult.error("DelegationExpired: ${e.expireReason ?: "no_reason"}")
            } catch (e: DelegationException.WriteFailed) {
                ToolResult.error("DelegationWriteFailed: ${e.ioReason}")
            }
        }

        // Fresh-send branch (existing logic, unchanged).
        return try {
            val handle = outboundService.send(
                request = request,
                suggestedRepo = suggestedRepo,
                delegatorSessionId = delegatorSessionId,
                onResult = { h, result ->
                    val nudge = buildNudgeText(h.targetRepoName, h.id, result)
                    agentService.enqueueNudgeForSession(delegatorSessionId, nudge)
                },
            )

            val shortId = handle.id.take(8)
            val content = buildString {
                appendLine("""{"handle":"${handle.id}","status":"running","repo":"${handle.targetRepoName}"}""")
                appendLine()
                appendLine(
                    "Delegated to ${handle.targetRepoName} (handle $shortId). " +
                    "Async — result will arrive as a nudge when done."
                )
            }.trimEnd()

            ToolResult(
                content = content,
                summary = "Delegated to ${handle.targetRepoName} ($shortId) — awaiting result",
                tokenEstimate = 30,
            )
        } catch (e: DelegationException.UserCanceledPicker) {
            ToolResult.error("DelegationUserCanceledPicker: user dismissed the picker — delegation not sent")
        } catch (e: DelegationException.TargetNotReachable) {
            ToolResult.error("DelegationTargetNotReachable: could not connect to the target IDE — is it running and has a session open?")
        } catch (e: DelegationException.LimitReached) {
            ToolResult.error("DelegationLimitReached: max ${DelegationOutboundService.MAX_CHANNELS} concurrent delegations already open — close one before sending another")
        } catch (e: DelegationException.Rejected) {
            ToolResult.error("DelegationRejected: the target IDE declined the request — reason: ${e.rejectReason ?: "none"}")
        }
    }

    // ── Nudge builder ────────────────────────────────────────────────────────

    /**
     * Builds the nudge text that is injected into the delegator's loop when the
     * delegated session terminates.
     */
    private fun buildNudgeText(
        repoName: String,
        handleId: String,
        result: DelegationMessage.Result,
    ): String = buildString {
        val shortId = handleId.take(8)
        appendLine("[DELEGATION RESULT — $repoName ($shortId)]")
        appendLine("Status: ${result.status}")
        if (result.summary.isNotBlank()) {
            appendLine("Summary: ${result.summary}")
        }
        if (result.filesChanged.isNotEmpty()) {
            appendLine("Files changed (${result.filesChanged.size}): ${result.filesChanged.joinToString(", ")}")
        }
        if (result.branch != null) {
            appendLine("Branch: ${result.branch}")
        }
        if (result.commit != null) {
            appendLine("Commit: ${result.commit}")
        }
        if (result.reason != null) {
            appendLine("Reason: ${result.reason}")
        }
        appendLine("Duration: ${result.durationSeconds}s")
        when (result.status) {
            DelegationMessage.ResultStatus.COMPLETED ->
                appendLine("The remote agent has finished. Review the result above and continue.")
            DelegationMessage.ResultStatus.CANCELED ->
                appendLine("The remote session was cancelled. You may retry delegation_send if needed.")
            DelegationMessage.ResultStatus.REJECTED ->
                appendLine("The remote agent rejected the task. Check the reason above.")
            DelegationMessage.ResultStatus.FAILED ->
                appendLine("The remote agent failed. Check the reason above; you may retry.")
        }
    }.trimEnd()

    companion object {
        private val LOG = Logger.getInstance(DelegationSendTool::class.java)
    }
}

package com.workflow.orchestrator.agent.tools.delegation

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.delegation.DelegationOutboundService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `delegation_close` — close an active delegation channel by handle.
 *
 * The remote Agent-B will finalize and return its partial-progress summary.
 * Idempotent — closing an already-closed handle is a no-op success.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §4.3.
 */
class DelegationCloseTool : AgentTool {

    override val name = "delegation_close"

    override val description = """
        Close an active delegation channel. The remote Agent-B will finalize and return its
        partial-progress summary.

        Idempotent — closing an already-closed handle is a no-op success.

        When to use:
        - You want to cancel a delegation that is taking too long and recover control.
        - The delegated task is no longer needed (e.g. the parent task was superseded).
        - You received a partial result via nudge and want to clean up the channel.

        How it works:
        1. The local SocketChannel for the handle is closed immediately.
        2. The remote Agent-B session will detect the disconnect and terminate.
        3. If the handle was already closed (or was never open), the call succeeds silently.

        Args:
          handle  (required) The channel handle returned by delegation_send.

        Returns on success:
          JSON with "closed" (true if a channel was found and closed, false if already closed)
          and "handle" (the id you passed in). Both outcomes are success — never an error.
        Returns on failure:
          Only if the "handle" argument is missing.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "handle" to ParameterProperty(
                type = "string",
                description = "The channel handle returned by delegation_send."
            ),
        ),
        required = listOf("handle"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.ANALYZER,
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // F3: runtime gate — closing a channel when outbound is disabled in the middle of a
        // session should still work (there may be open channels from before the toggle), but
        // the same gate is applied for consistency: if outbound was never enabled the tool
        // couldn't have sent anything, so "no open channels" is the correct semantics.
        if (!PluginSettings.getInstance(project).state.enableOutboundCrossIdeDelegation) {
            return ToolResult.error(
                "DelegationOutboundDisabled: cross-IDE delegation is currently disabled in settings " +
                    "(Tools → Workflow Orchestrator → Agent → Enable outbound cross-IDE delegation)"
            )
        }

        val handle = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation_close: 'handle' is required")

        val outboundService = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation_close: DelegationOutboundService unavailable")

        val closed = outboundService.close(handle)

        val shortId = handle.take(8)
        val summary = if (closed) {
            "Closed delegation $shortId"
        } else {
            "Handle $shortId already closed"
        }

        val content = """{"closed":$closed,"handle":"$handle"}"""

        LOG.debug("[DelegationClose] handle=$shortId closed=$closed")

        return ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = 15,
        )
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationCloseTool::class.java)
    }
}

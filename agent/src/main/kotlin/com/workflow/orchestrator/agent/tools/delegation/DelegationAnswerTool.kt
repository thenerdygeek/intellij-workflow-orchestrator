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
 * `delegation_answer` — reply to a clarifying question raised by a delegated session.
 *
 * When Agent-B raises a [DelegationMessage.Question] while working on a delegated task,
 * Agent-A receives it as a nudge. This tool allows Agent-A to write an Answer back over
 * the still-open IPC channel so Agent-B can proceed.
 *
 * Returns a distinct error when the handle is unknown or closed so the LLM can
 * recognize that the channel has expired since the Question arrived.
 *
 * Spec: docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §4.2.
 */
class DelegationAnswerTool : AgentTool {

    override val name = "delegation_answer"

    override val description = """
        Reply to a clarifying question raised by a delegated session.

        When a delegated Agent-B cannot continue without more information, it raises a Question
        nudge that is injected into this session. Use this tool to forward your answer back over
        the open IPC channel so Agent-B can proceed.

        Args:
          handle      (required) The channel handle returned by delegation_send.
          question_id (required) The questionId from the Question nudge.
          answer      (required) The text to forward to the delegated session.

        Returns on success:
          JSON with "sent" (true), "handle", and "question_id".
        Returns on failure:
          Distinct error when the handle is unknown or the channel has closed since the
          Question arrived. In that case the delegated session has likely already terminated;
          use delegation_send to start a new one if needed.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "handle" to ParameterProperty(
                type = "string",
                description = "The channel handle returned by delegation_send."
            ),
            "question_id" to ParameterProperty(
                type = "string",
                description = "The questionId from the Question nudge received from the delegated session."
            ),
            "answer" to ParameterProperty(
                type = "string",
                description = "The answer text to forward to the delegated session."
            ),
        ),
        required = listOf("handle", "question_id", "answer"),
    )

    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.ANALYZER,
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // F3: runtime gate — same as delegation_send and delegation_close. If outbound
        // delegation was disabled mid-session, open channels may still exist, but we
        // gate consistently on the setting to surface a clear error rather than a
        // confusing "Unknown tool" if the tool was unregistered after a hot-toggle.
        if (!PluginSettings.getInstance(project).state.enableOutboundCrossIdeDelegation) {
            return ToolResult.error(
                "DelegationOutboundDisabled: cross-IDE delegation is currently disabled in settings " +
                    "(Tools → Workflow Orchestrator → Agent → Enable outbound cross-IDE delegation)"
            )
        }

        val handleId = params["handle"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation_answer: 'handle' is required")
        val questionId = params["question_id"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation_answer: 'question_id' is required")
        val answerText = params["answer"]?.jsonPrimitive?.content
            ?: return ToolResult.error("delegation_answer: 'answer' is required")

        val outboundService = project.getService(DelegationOutboundService::class.java)
            ?: return ToolResult.error("delegation_answer: DelegationOutboundService unavailable")

        val sent = outboundService.sendAnswer(handleId, questionId, answerText)

        val shortId = handleId.take(8)
        return if (sent) {
            LOG.debug("[DelegationAnswer] handle=$shortId question=$questionId sent=true")
            ToolResult(
                content = """{"sent":true,"handle":"$handleId","question_id":"$questionId"}""",
                summary = "Sent answer to $shortId",
                tokenEstimate = 15,
            )
        } else {
            ToolResult.error(
                "delegation_answer: handle $handleId is unknown or closed; cannot send answer. " +
                    "The delegated session may have already terminated."
            )
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DelegationAnswerTool::class.java)
    }
}

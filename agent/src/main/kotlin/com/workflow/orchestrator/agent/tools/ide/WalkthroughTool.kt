package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.agent.ui.AgentControllerRegistry
import com.workflow.orchestrator.agent.walkthrough.StepValidation
import com.workflow.orchestrator.agent.walkthrough.WalkthroughService
import com.workflow.orchestrator.agent.walkthrough.WalkthroughServiceApi
import com.workflow.orchestrator.agent.walkthrough.WalkthroughStep
import com.workflow.orchestrator.agent.walkthrough.defaultStepValidator
import com.workflow.orchestrator.agent.walkthrough.parseStepsJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Guided code tour meta-tool. Every action returns immediately — the user pages
 * through the tour at their own pace while the agent keeps appending in the
 * background (producer/consumer; spec 2026-06-11-code-walkthrough-design.md).
 * NOT in WRITE_TOOLS (read-only -> plan-mode legal); excluded from sub-agents via
 * SpawnAgentTool's name filter (allowedWorkers is documentation, not enforcement).
 */
class WalkthroughTool(
    private val serviceProvider: (Project) -> WalkthroughServiceApi =
        { p -> p.getService(WalkthroughService::class.java) },
    private val stepValidator: suspend (Project, List<WalkthroughStep>) -> StepValidation =
        ::defaultStepValidator,
    private val interactiveGuard: (Project) -> String? = ::defaultInteractiveGuard,
    private val edtDispatcherOverride: CoroutineDispatcher? = null,
) : AgentTool {

    override val name = "walkthrough"

    override val description =
        "Guided code tour: open files, highlight line ranges, and explain code step-by-step in a callout " +
            "box the user pages through with Next/Back. STREAMING PATTERN — call action=start as soon as you " +
            "know the first 1-2 steps, then keep exploring and call action=append for further steps as you " +
            "find them; call action=finish when the tour is complete. Do NOT batch the whole tour into one " +
            "start call. When the user asks a question it arrives as a normal chat message prefixed with the " +
            "step location — answer it in chat. Optionally call action=update_step to revise or enrich a step " +
            "you already showed (e.g. distill your chat answer into the step's box): step=<1-based index>, " +
            "body_md=<markdown>, mode=append|replace. Append is non-jarring; prefer it for live tours. Each " +
            "call returns the tour status including which step the user is on; if it reports the user ended " +
            "the tour, stop appending."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "One of: start (create tour + show step 1), append (add steps to the active " +
                    "tour), finish (mark the queue complete), update_step (revise/enrich an already-shown " +
                    "step's body by 1-based index).",
                enumValues = listOf("start", "append", "finish", "update_step"),
            ),
            "title" to ParameterProperty(
                type = "string",
                description = "Short tour title shown in the callout header (start only), " +
                    "e.g. \"How a tool call flows\".",
            ),
            "steps" to ParameterProperty(
                type = "string",
                description = "JSON array AS A STRING — required for start/append. Each element: " +
                    """{"file": "path (project-relative or absolute)", "start_line": 1-based, """ +
                    """"end_line": 1-based inclusive, "title": "optional", "body_md": "markdown explanation"}.""",
            ),
            "step" to ParameterProperty(
                type = "string",
                description = "1-based index of the step to update (update_step only).",
            ),
            "mode" to ParameterProperty(
                type = "string",
                description = "update_step write mode: append (default) adds under the existing text; " +
                    "replace rewrites it.",
                enumValues = listOf("append", "replace"),
            ),
            "body_md" to ParameterProperty(
                type = "string",
                description = "Markdown body for the step (update_step only).",
            ),
        ),
        required = listOf("action"),
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        interactiveGuard(project)?.let { return ToolResult.error(it) }
        val action = (params["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.error("walkthrough: missing required parameter 'action'")
        val service = serviceProvider(project)
        val edt = edtDispatcherOverride ?: Dispatchers.EDT
        return when (action) {
            "start", "append" -> {
                val parse = parseStepsJson(params["steps"])
                val validation = stepValidator(project, parse.steps)
                val allErrors = parse.errors + validation.errors
                if (validation.valid.isEmpty()) {
                    return ToolResult.error(
                        "walkthrough $action: no valid steps.\n" + allErrors.joinToString("\n")
                    )
                }
                val title = (params["title"] as? JsonPrimitive)?.contentOrNull
                val feedback = withContext(edt) {
                    if (action == "start") {
                        service.startTour(title, validation.valid)
                    } else {
                        service.appendSteps(validation.valid)
                    }
                }
                if (!feedback.ok) return ToolResult.error(feedback.message)
                val rejected = if (allErrors.isEmpty()) {
                    ""
                } else {
                    "\nRejected steps (fix and re-append):\n" + allErrors.joinToString("\n")
                }
                val content = feedback.message + rejected
                ToolResult(
                    content = content,
                    summary = "Walkthrough: $action ${validation.valid.size} step(s) — ${feedback.message}",
                    tokenEstimate = estimateTokens(content),
                )
            }
            "finish" -> {
                val feedback = withContext(edt) { service.finishTour() }
                if (!feedback.ok) {
                    ToolResult.error(feedback.message)
                } else {
                    ToolResult(
                        content = feedback.message,
                        summary = "Walkthrough finished — ${feedback.message}",
                        tokenEstimate = estimateTokens(feedback.message),
                    )
                }
            }
            "update_step" -> {
                val stepIndex = (params["step"] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
                    ?: return ToolResult.error(
                        "walkthrough update_step: 'step' must be a 1-based integer index"
                    )
                val body = (params["body_md"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return ToolResult.error("walkthrough update_step: missing required parameter 'body_md'")
                val mode = (params["mode"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "append"
                val append = mode != "replace"
                val feedback = withContext(edt) { service.updateStep(stepIndex, body, append) }
                if (!feedback.ok) {
                    ToolResult.error(feedback.message)
                } else {
                    ToolResult(
                        content = feedback.message,
                        summary = "Walkthrough step $stepIndex updated (${if (append) "append" else "replace"})",
                        tokenEstimate = estimateTokens(feedback.message),
                    )
                }
            }
            else -> ToolResult.error(
                "walkthrough: unknown action '$action' — expected start | append | finish | update_step"
            )
        }
    }
}

/**
 * Tours need the interactive chat: delegated sessions and background/monitor wake runs
 * never reach AgentController.onComplete, so the auto-finish guarantee can't hold there.
 */
internal fun defaultInteractiveGuard(project: Project): String? {
    AgentControllerRegistry.getInstance(project).controller
        ?: return "walkthrough requires the interactive agent chat (no controller attached)"
    // getServiceIfCreated + null-safe chain: during teardown/partial init the service may be
    // null or uninitialized — degrade to "allowed" (the controller check above gates the
    // real concern) instead of crashing the tool.
    val service = project.getServiceIfCreated(com.workflow.orchestrator.agent.AgentService::class.java)
    if (service?.currentSessionState()?.delegated != null) {
        return "walkthrough is unavailable in delegated sessions — it requires the interactive agent chat"
    }
    return null
}

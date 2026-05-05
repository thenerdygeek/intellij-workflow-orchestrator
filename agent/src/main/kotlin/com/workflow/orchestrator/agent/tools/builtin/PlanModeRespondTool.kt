package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Plan mode response tool — faithful port of Cline's plan_mode_respond.
 *
 * Cline source: src/core/prompts/system-prompt/tools/plan_mode_respond.ts
 *
 * This tool is only available in PLAN MODE. The LLM uses it to present a
 * concrete plan after exploring relevant files. The plan is returned to the
 * user for review. If needs_more_exploration is true, the loop continues
 * to let the LLM explore more before presenting a final plan.
 *
 * Flow:
 * 1. LLM explores code (reads, searches) in plan mode
 * 2. LLM calls plan_mode_respond with its plan
 * 3. If needs_more_exploration=true, loop continues for more exploration
 * 4. If needs_more_exploration=false (default), loop pauses for user review
 * 5. User reviews, gives feedback or approves
 * 6. On approval, switches to act mode to implement
 */
class PlanModeRespondTool : AgentTool {

    private val LOG = Logger.getInstance(PlanModeRespondTool::class.java)

    override val name = "plan_mode_respond"

    override val description = "Call this ONLY when presenting a new or materially revised implementation plan. " +
        "For conversational replies (answering questions, acknowledging feedback, discussing whether to plan) " +
        "reply with plain text — do not call this tool. If a previously presented plan has become invalid and " +
        "you do not have a replacement ready, call discard_plan to clear it.\n\n" +
        "Present a concrete implementation plan to the user for review and approval. " +
        "This tool should ONLY be used when you have already explored the relevant files and are ready to present " +
        "a plan. DO NOT use this tool to announce what files you're going to read — just read them first. " +
        "This tool is only available in PLAN MODE.\n\n" +
        "Your plan is a single `response` field: a full markdown document with headings, code blocks, tables, " +
        "and file paths. This is rendered in the plan document viewer where the user can add inline comments.\n\n" +
        "Plan format guidelines:\n" +
        "- Use `## Phase N: Title` or `### Task N: Title` headings to structure the response markdown.\n" +
        "- Under each heading, list the files to create/modify, the steps to take, and include actual code blocks.\n\n" +
        "Callouts (admonitions): the plan viewer renders GitHub-style alert blockquotes as colored " +
        "callout boxes. Use them as a SINGLE \"things the user should glance at before approving\" zone at " +
        "the very TOP of the plan — before the first `### Task` header, in the summary area. Do NOT sprinkle " +
        "callouts through individual tasks; that's just visual noise. The whole point is that the user can " +
        "scan one block at the top and immediately see whether their input is needed.\n\n" +
        "Syntax:\n" +
        "  > [!LABEL]\n" +
        "  > One or more lines of body text. Inline `code`, **bold**, [links](url) and lists are fine.\n\n" +
        "Recommended labels (each maps to a distinct callout color):\n" +
        "- `[!REVIEW REQUIRED]` — the user must verify or decide something before you can continue " +
        "(e.g. \"confirm column type\", \"choose between Option A and B\"). This is the most important label.\n" +
        "- `[!ASSUMPTION]` — something you assumed; flag it so the user can correct it cheaply before approval.\n" +
        "- `[!RISK]` — a known trade-off or sharp edge in the chosen approach.\n" +
        "- `[!IMPORTANT]` — must-read info that affects correctness.\n" +
        "- `[!WARNING]` — proceed-with-care detail; behaviour changes if missed.\n" +
        "- `[!CAUTION]` — risk of data loss, breaking change, security impact.\n" +
        "- `[!NOTE]` — neutral context the reader should know.\n" +
        "- `[!TIP]` — a helpful suggestion or shortcut.\n" +
        "Custom labels are accepted (e.g. `[!ROLLBACK PLAN]`) and render with a generic style.\n\n" +
        "Rules of thumb: zero callouts is fine — use them only when the user's attention is genuinely " +
        "needed. Two or three at most for a typical plan. If you have nothing the user must review, " +
        "skip callouts entirely — an empty top-zone is cleaner than a fake `[!NOTE]` filler.\n\n" +
        "If while writing your response you realize you need more exploration, set needs_more_exploration=true."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "response" to ParameterProperty(
                type = "string",
                description = "The full implementation plan as a markdown document. Use ## and ### headings for " +
                    "phases/tasks, include file paths, code blocks, tables, and step-by-step instructions. " +
                    "This is rendered in the plan document viewer where the user can add inline comments on " +
                    "specific lines. Do not use tools in this parameter — it is a markdown document only."
            ),
            "needs_more_exploration" to ParameterProperty(
                type = "boolean",
                description = "Set to true if while formulating your response you found you need to do more " +
                    "exploration with tools, for example reading files. (Remember, you can explore the project " +
                    "with tools like read_file in PLAN MODE without the user having to toggle to ACT MODE.) " +
                    "Defaults to false if not specified."
            )
        ),
        required = listOf("response")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val response = params["response"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: response",
                summary = "plan_mode_respond failed: missing response",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val needsMoreExploration = try {
            params["needs_more_exploration"]?.jsonPrimitive?.boolean ?: false
        } catch (_: Exception) {
            // Handle string "true"/"false" since some models send booleans as strings
            params["needs_more_exploration"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) ?: false
        }

        return ToolResult.planResponse(
            content = response,
            summary = if (needsMoreExploration) {
                "Plan draft (needs more exploration): ${response.take(200)}"
            } else {
                "Plan presented: ${response.take(200)}"
            },
            tokenEstimate = response.length / 4,
            needsMoreExploration = needsMoreExploration
        )
    }
}

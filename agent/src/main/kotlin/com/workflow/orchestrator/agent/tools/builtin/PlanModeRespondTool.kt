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
import kotlinx.serialization.json.jsonArray
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

    override val description = "Present a concrete implementation plan to the user for review and approval. " +
        "This tool should ONLY be used when you have already explored the relevant files and are ready to present " +
        "a plan. DO NOT use this tool to announce what files you're going to read — just read them first. " +
        "This tool is only available in PLAN MODE.\n\n" +
        "Your plan has two parts:\n" +
        "- `response`: A full markdown document with headings, code blocks, tables, and file paths. " +
        "This is rendered in the plan document viewer where the user can add inline comments.\n" +
        "- `steps`: A JSON array of high-level phase/task titles (typically 5-10). These appear in the " +
        "plan progress card and are tracked during execution via task_progress.\n\n" +
        "Plan format guidelines:\n" +
        "- Use `## Phase N: Title` or `### Task N: Title` headings to structure the response markdown.\n" +
        "- Under each heading, list the files to create/modify, the steps to take, and include actual code blocks.\n" +
        "- The `steps` array should contain one title per phase/task — these are what the user sees in the " +
        "progress card (e.g. [\"Set up project structure\", \"Implement core logic\", \"Add tests\", \"Verify\"]).\n" +
        "- After approval, include task_progress in your tool calls with matching titles to update progress.\n\n" +
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
            "steps" to ParameterProperty(
                type = "array",
                description = "A JSON array of high-level plan step titles (strings). These are the phases/tasks " +
                    "the user will see in the plan progress card. Keep them concise and meaningful — typically " +
                    "5-10 items. Example: [\"Set up project structure\", \"Implement core logic\", \"Add tests\"]",
                items = ParameterProperty(type = "string", description = "A plan step title")
            ),
            "needs_more_exploration" to ParameterProperty(
                type = "boolean",
                description = "Set to true if while formulating your response you found you need to do more " +
                    "exploration with tools, for example reading files. (Remember, you can explore the project " +
                    "with tools like read_file in PLAN MODE without the user having to toggle to ACT MODE.) " +
                    "Defaults to false if not specified."
            ),
            "task_progress" to ParameterProperty(
                type = "string",
                description = "A checklist showing task progress after this tool use is completed. If you have " +
                    "presented the user with concrete steps or requirements, you can optionally include a todo " +
                    "list outlining these steps."
            )
        ),
        required = listOf("response", "steps")
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

        // Extract structured step titles from the mandatory steps array
        val planSteps = try {
            params["steps"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        } catch (e: Exception) {
            LOG.warn("plan_mode_respond: malformed steps parameter, expected JSON array of strings: ${e.message}")
            emptyList()
        }

        if (planSteps.isEmpty() && !needsMoreExploration) {
            LOG.warn("plan_mode_respond: steps array is empty — plan card will show no steps")
        }

        return ToolResult(
            content = response,
            summary = if (needsMoreExploration) {
                "Plan draft (needs more exploration): ${response.take(200)}"
            } else {
                "Plan presented (${planSteps.size} steps): ${response.take(200)}"
            },
            tokenEstimate = response.length / 4,
            isPlanResponse = true,
            needsMoreExploration = needsMoreExploration,
            planSteps = planSteps
        )
    }
}

package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.AgentPlan
import com.workflow.orchestrator.agent.runtime.PlanApprovalResult
import com.workflow.orchestrator.agent.runtime.PlanStep
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class CreatePlanTool : AgentTool {
    override val name = "create_plan"
    override val description = "Create an implementation plan before making changes. Use for complex tasks " +
        "involving 3+ files, refactoring, or new features. Write the plan as a markdown document — " +
        "steps are automatically extracted from ### headings. The plan will be shown to the user " +
        "for review and approval before execution begins. Do NOT use for simple questions or single-file fixes."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(
                type = "string",
                description = "Short title for the plan (shown in the chat card header)"
            ),
            "markdown" to ParameterProperty(
                type = "string",
                description = "Full implementation plan as a markdown document. Structure:\n" +
                    "## Goal — what the task achieves (1-2 sentences)\n" +
                    "## Approach — high-level strategy\n" +
                    "## Steps — use ### for each step (e.g., ### 1. Create FileRegistry). " +
                    "Include file paths, code blocks, and detailed explanations.\n" +
                    "## Testing — how to verify the implementation\n\n" +
                    "Steps are auto-extracted from ### headings for progress tracking."
            )
        ),
        required = listOf("title", "markdown")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'title' parameter required", "Error: missing title", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val markdown = params["markdown"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'markdown' parameter required", "Error: missing markdown", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (markdown.isBlank()) {
            return ToolResult("Error: markdown must not be empty", "Error: empty markdown", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Extract steps from ### headings in the markdown
        val steps = extractStepsFromMarkdown(markdown)
        if (steps.isEmpty()) {
            return ToolResult(
                "Error: no steps found in markdown. Use ### headings for each step " +
                    "(e.g., '### 1. Create FileRegistry'). Steps are auto-extracted for progress tracking.",
                "Error: no steps in markdown", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // Extract goal from ## Goal section (first paragraph after ## Goal heading)
        val goal = extractSection(markdown, "Goal") ?: title

        val planManager = try {
            AgentService.getInstance(project).currentPlanManager
        } catch (_: Exception) { null }

        if (planManager == null) {
            return ToolResult("Error: no active session for plan management", "Error: no session", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val plan = AgentPlan(
            goal = goal,
            steps = steps,
            title = title,
            markdown = markdown
        )

        // Submit plan and suspend until user approves or revises.
        val result = try {
            withTimeoutOrNull(600_000L) { // 10 minute timeout
                suspendCancellableCoroutine<PlanApprovalResult> { cont ->
                    val future = planManager.submitPlan(plan)
                    cont.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { value, error ->
                        if (error != null) {
                            if (!cont.isCompleted) cont.resumeWithException(error)
                        } else {
                            if (!cont.isCompleted) cont.resume(value)
                        }
                    }
                }
            } ?: return ToolResult(
                "Plan approval timed out after 10 minutes. Please try again.",
                "Plan timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (e: CancellationException) {
            return ToolResult(
                "Plan approval was cancelled.",
                "Plan cancelled", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return when (result) {
            is PlanApprovalResult.Approved -> {
                ToolResult(
                    content = "Plan approved by user. Execute the plan step by step. For simple steps " +
                        "(1-2 files), handle them directly. For complex steps (3+ files, multi-step edits), " +
                        "use agent(subagent_type=\"coder\") to spawn a focused worker. Update step status " +
                        "with update_plan_step as you progress ('running' when starting, 'done' when " +
                        "complete, 'failed' if it fails).",
                    summary = "Plan approved (${steps.size} steps)",
                    tokenEstimate = 40
                )
            }
            is PlanApprovalResult.Revised -> {
                val comments = result.comments.entries.joinToString("\n") { "- Step ${it.key}: ${it.value}" }
                ToolResult(
                    content = "User requested revisions to the plan. Their feedback:\n$comments\n\n" +
                        "Please create a revised plan by calling create_plan again with updated " +
                        "markdown incorporating this feedback.",
                    summary = "Plan revision requested",
                    tokenEstimate = TokenEstimator.estimate(comments),
                    isError = true
                )
            }
            is PlanApprovalResult.RevisedWithContext -> {
                val feedbackLines = result.revisions.joinToString("\n\n") { rev ->
                    "On: \"${rev.line}\"\n→ User: \"${rev.comment}\""
                }
                val planSection = if (result.fullMarkdown != null) {
                    "\n\n<current_plan_markdown>\n${result.fullMarkdown}\n</current_plan_markdown>"
                } else ""
                val content = buildString {
                    appendLine("User requested revisions to the plan. Their inline feedback:")
                    appendLine()
                    appendLine(feedbackLines)
                    appendLine()
                    append("Here is the full plan for reference:")
                    append(planSection)
                    appendLine()
                    appendLine()
                    append("Please create a revised plan by calling create_plan again, addressing all feedback above.")
                }
                ToolResult(
                    content = content,
                    summary = "Plan revision requested (${result.revisions.size} comments)",
                    tokenEstimate = TokenEstimator.estimate(content),
                    isError = true
                )
            }
        }
    }

    companion object {
        /**
         * Extract steps from ### headings in markdown.
         *
         * Matches patterns like:
         * - `### 1. Create FileRegistry`
         * - `### Step 1: Create FileRegistry`
         * - `### Create FileRegistry`
         *
         * Returns [PlanStep] with auto-generated IDs and titles from headings.
         * File paths are extracted from backtick-quoted paths in the step's content.
         */
        fun extractStepsFromMarkdown(markdown: String): List<PlanStep> {
            val lines = markdown.lines()
            val steps = mutableListOf<PlanStep>()
            var stepIndex = 0
            var i = 0

            while (i < lines.size) {
                val line = lines[i]
                // Match ### headings (level 3) — these are steps
                // Skip ## headings (level 2) — those are sections (Goal, Approach, Steps, Testing)
                val match = STEP_HEADING_REGEX.matchEntire(line.trim())
                if (match != null) {
                    stepIndex++
                    val rawTitle = match.groupValues[1].trim()
                    // Strip leading number/bullet: "1. Title", "Step 1: Title", "1) Title"
                    val cleanTitle = rawTitle
                        .replace(LEADING_NUMBER_REGEX, "")
                        .replace(LEADING_STEP_REGEX, "")
                        .trim()
                    val title = cleanTitle.ifBlank { rawTitle }

                    // Collect content lines until next ### or ## heading
                    val contentLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("##")) {
                        contentLines.add(lines[i])
                        i++
                    }
                    val content = contentLines.joinToString("\n").trim()

                    // Extract file paths from backtick-quoted paths in content
                    val files = FILE_PATH_REGEX.findAll(content)
                        .map { it.groupValues[1] }
                        .filter { it.contains('/') || it.contains('.') } // must look like a path
                        .distinct()
                        .toList()

                    // Determine description (first non-empty paragraph)
                    val description = content.lines()
                        .dropWhile { it.isBlank() }
                        .takeWhile { it.isNotBlank() }
                        .joinToString(" ")
                        .take(200)

                    steps.add(PlanStep(
                        id = "step-$stepIndex",
                        title = title,
                        description = description,
                        files = files
                    ))
                } else {
                    i++
                }
            }
            return steps
        }

        /**
         * Extract the first paragraph content after a ## section heading.
         */
        fun extractSection(markdown: String, sectionName: String): String? {
            val regex = Regex("""^##\s+$sectionName\s*$""", RegexOption.MULTILINE)
            val match = regex.find(markdown) ?: return null
            val afterHeading = markdown.substring(match.range.last + 1).trimStart()
            // Take until the next ## heading or end
            val endIdx = afterHeading.indexOf("\n## ").let { if (it == -1) afterHeading.length else it }
            return afterHeading.substring(0, endIdx).trim().takeIf { it.isNotBlank() }
        }

        private val STEP_HEADING_REGEX = Regex("""^###\s+(.+)$""")
        private val LEADING_NUMBER_REGEX = Regex("""^\d+[.)]\s*""")
        private val LEADING_STEP_REGEX = Regex("""^[Ss]tep\s+\d+[.:]\s*""")
        private val FILE_PATH_REGEX = Regex("""`([^`]+\.[a-zA-Z]{1,10})`""")
    }
}

package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunResult
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunStats
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunStatus
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunner
import com.workflow.orchestrator.agent.tools.subagent.SubagentStatusItem
import com.workflow.orchestrator.core.ai.LlmBrain
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Claude Code-style sub-agent delegation tool.
 *
 * The parent LLM calls `agent` to delegate a task to a sub-agent that runs
 * in its own AgentLoop with a fresh ContextManager (context isolation).
 * The sub-agent's result flows back as a single ToolResult.
 *
 * Depth-1 hard limit: sub-agents CANNOT spawn further sub-agents.
 *
 * Supports up to 5 parallel prompts in research scope for fan-out research.
 */
class SpawnAgentTool(
    private val brainProvider: suspend () -> LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project,
    private val contextBudget: Int = DEFAULT_CONTEXT_BUDGET,
    private val onSubagentProgress: (suspend (String, SubagentProgressUpdate) -> Unit)? = null
) : AgentTool {

    override val name = "agent"

    override val description = """Launch a focused sub-agent to handle a task in its own context window. Each sub-agent gets its own prompt and returns a comprehensive result. Use this for broad exploration when reading many files would consume your main context window, or to delegate self-contained implementation work. You do not need to launch multiple sub-agents every time — using one sub-agent is valid when it avoids unnecessary context usage for focused work.

The sub-agent gets a FRESH context — it cannot see your conversation history. You MUST include all necessary context in the prompt: file paths, class names, what to look for or change, and why.

Use this when:
- A task is self-contained and you want to keep your context clean
- You need to explore/research code without polluting your main context
- You want to delegate implementation work (edit files, write tests, run builds)
- You need a focused agent for a specific sub-task of a larger plan

Do NOT use this when:
- The task requires your conversation context to understand
- A single tool call would suffice (don't over-delegate)
- You need interactive back-and-forth (sub-agents can't ask you questions)

Scopes:
- "research": Read-only exploration. Use for understanding code, finding patterns, analyzing architecture.
- "implement": Full write access. Use for coding — editing files, creating files, running commands, running tests.
- "review": Read + diagnostics. Use for code review, finding bugs, checking quality.

Parallel execution (research scope only):
- In research scope, you can provide up to 5 prompts (prompt, prompt_2, ..., prompt_5).
- Each prompt runs as a separate parallel subagent with its own context.
- Use this to fan out multiple research questions simultaneously.
- For implement/review scopes, only the primary prompt is used (sequential).

Tips:
- Be specific in the prompt. "Fix the bug in UserService" is bad. "In src/main/kotlin/com/example/UserService.kt, the login() method at line 45 throws NPE when email is null. Add a null check and return an error result." is good.
- Include file paths. The sub-agent starts with zero context.
- For implementation tasks, tell the agent to verify its work (run tests, check compilation)."""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "description" to ParameterProperty(
                type = "string",
                description = "Short 3-5 word description of what the agent will do (e.g., 'Fix null check in UserService')"
            ),
            "prompt" to ParameterProperty(
                type = "string",
                description = "Complete task description. Include ALL context the agent needs — file paths, class names, what to look for or change, and why. The agent has NO access to your conversation history."
            ),
            "prompt_2" to ParameterProperty(
                type = "string",
                description = "Optional second research prompt (research scope only, parallel execution)."
            ),
            "prompt_3" to ParameterProperty(
                type = "string",
                description = "Optional third research prompt (research scope only, parallel execution)."
            ),
            "prompt_4" to ParameterProperty(
                type = "string",
                description = "Optional fourth research prompt (research scope only, parallel execution)."
            ),
            "prompt_5" to ParameterProperty(
                type = "string",
                description = "Optional fifth research prompt (research scope only, parallel execution)."
            ),
            "scope" to ParameterProperty(
                type = "string",
                description = "Agent scope: 'research' (read-only), 'implement' (full write access), or 'review' (read + diagnostics). Defaults to 'implement'.",
                enumValues = listOf("research", "implement", "review")
            ),
            "max_iterations" to ParameterProperty(
                type = "integer",
                description = "Max iterations for the sub-agent (5-100, default 50). Lower = faster/cheaper."
            )
        ),
        required = listOf("description", "prompt")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val description = params["description"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: description")
        val prompt = params["prompt"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: prompt")
        val scope = params["scope"]?.jsonPrimitive?.content ?: "implement"
        val maxIter = params["max_iterations"]?.jsonPrimitive?.intOrNull ?: 50

        if (scope !in VALID_SCOPES) {
            return errorResult("Invalid scope: '$scope'. Must be one of: research, implement, review")
        }

        val clampedIterations = maxIter.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

        // Collect prompts: for research scope, gather from all PROMPT_KEYS; otherwise just primary
        val prompts = if (scope == "research") {
            PROMPT_KEYS.mapNotNull { key -> params[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }
        } else {
            listOf(prompt)
        }

        if (prompts.isEmpty()) {
            return errorResult("No valid prompts provided")
        }

        return if (prompts.size == 1) {
            executeSingle(description, prompts.first(), scope, clampedIterations)
        } else {
            executeParallel(description, prompts, scope, clampedIterations)
        }
    }

    // ---- Single subagent execution ----

    private suspend fun executeSingle(
        description: String,
        prompt: String,
        scope: String,
        maxIterations: Int
    ): ToolResult {
        val brain = brainProvider()
        val scopedTools = resolveScopedTools(scope)
        val systemPrompt = buildSubAgentPrompt(scope)

        val runner = SubagentRunner(
            brain = brain,
            tools = scopedTools,
            systemPrompt = systemPrompt,
            project = project,
            maxIterations = maxIterations,
            planMode = scope != "implement",
            contextBudget = contextBudget
        )

        val result = runner.run(prompt) { progress ->
            onSubagentProgress?.invoke(description, progress)
        }

        return mapSingleResult(description, scope, result)
    }

    private fun mapSingleResult(
        description: String,
        scope: String,
        result: SubagentRunResult
    ): ToolResult {
        val statsLine = formatStatsLine(result.stats)

        return when (result.status) {
            SubagentRunStatus.COMPLETED -> ToolResult(
                content = "[Agent: $description]\n${result.result ?: "(no output)"}\n\n$statsLine",
                summary = "Agent completed ($scope): ${(result.result ?: "").take(150)}",
                tokenEstimate = estimateTokens(result.result ?: ""),
                verifyCommand = null
            )
            SubagentRunStatus.FAILED -> ToolResult(
                content = "[Agent: $description] Failed: ${result.error ?: "unknown error"}\n\n$statsLine",
                summary = "Agent failed: ${(result.error ?: "").take(100)}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ---- Parallel subagent execution (research scope only) ----

    private suspend fun executeParallel(
        description: String,
        prompts: List<String>,
        scope: String,
        maxIterations: Int
    ): ToolResult {
        val scopedTools = resolveScopedTools(scope)
        val systemPrompt = buildSubAgentPrompt(scope)

        // Create status entries for each prompt
        val entries = prompts.mapIndexed { idx, p ->
            SubagentStatusItem(
                index = idx,
                prompt = excerpt(p),
                status = "pending"
            )
        }

        // Run all prompts in parallel using supervisorScope
        val results = supervisorScope {
            prompts.mapIndexed { idx, p ->
                async {
                    val brain = brainProvider()
                    val runner = SubagentRunner(
                        brain = brain,
                        tools = scopedTools,
                        systemPrompt = systemPrompt,
                        project = project,
                        maxIterations = maxIterations,
                        planMode = true, // research scope is always plan mode
                        contextBudget = contextBudget
                    )

                    entries[idx].status = "running"
                    emitGroupProgress(description, entries)

                    try {
                        val result = runner.run(p) { progress ->
                            // Update the entry with progress info
                            progress.stats?.let { stats ->
                                entries[idx].toolCalls = stats.toolCalls
                                entries[idx].inputTokens = stats.inputTokens
                                entries[idx].outputTokens = stats.outputTokens
                                entries[idx].totalCost = stats.totalCost
                                entries[idx].contextTokens = stats.contextTokens
                                entries[idx].contextWindow = stats.contextWindow
                                entries[idx].contextUsagePercentage = stats.contextUsagePercentage
                            }
                            progress.latestToolCall?.let { entries[idx].latestToolCall = it }
                            emitGroupProgress(description, entries)
                        }

                        // Update entry with final result
                        when (result.status) {
                            SubagentRunStatus.COMPLETED -> {
                                entries[idx].status = "completed"
                                entries[idx].result = result.result
                            }
                            SubagentRunStatus.FAILED -> {
                                entries[idx].status = "failed"
                                entries[idx].error = result.error
                            }
                        }
                        emitGroupProgress(description, entries)
                        result
                    } catch (e: Exception) {
                        entries[idx].status = "failed"
                        entries[idx].error = e.message ?: "Unknown error"
                        emitGroupProgress(description, entries)
                        SubagentRunResult(
                            status = SubagentRunStatus.FAILED,
                            error = e.message ?: "Unknown error"
                        )
                    }
                }
            }.map { it.await() }
        }

        // Build summary
        val succeeded = results.count { it.status == SubagentRunStatus.COMPLETED }
        val failed = results.count { it.status == SubagentRunStatus.FAILED }
        val total = results.size

        val totalStats = aggregateStats(results)
        val statsLine = formatStatsLine(totalStats)

        val content = buildString {
            appendLine("[Parallel agents: $description]")
            appendLine("Total: $total | Succeeded: $succeeded | Failed: $failed")
            appendLine()

            results.forEachIndexed { idx, result ->
                val promptExcerpt = excerpt(prompts[idx], 80)
                appendLine("--- Agent ${idx + 1}: $promptExcerpt ---")
                when (result.status) {
                    SubagentRunStatus.COMPLETED -> {
                        appendLine(excerpt(result.result ?: "(no output)"))
                    }
                    SubagentRunStatus.FAILED -> {
                        appendLine("FAILED: ${result.error ?: "unknown error"}")
                    }
                }
                appendLine()
            }

            appendLine(statsLine)
        }

        val summary = "Parallel agents ($succeeded/$total succeeded): ${description.take(100)}"
        val hasErrors = failed > 0

        return ToolResult(
            content = content,
            summary = summary,
            tokenEstimate = estimateTokens(content),
            isError = hasErrors && succeeded == 0 // only error if ALL failed
        )
    }

    private suspend fun emitGroupProgress(description: String, entries: List<SubagentStatusItem>) {
        val completed = entries.count { it.status == "completed" || it.status == "failed" }
        val status = if (completed == entries.size) "completed" else "running"
        onSubagentProgress?.invoke(
            description,
            SubagentProgressUpdate(status = status)
        )
    }

    private fun aggregateStats(results: List<SubagentRunResult>): SubagentRunStats {
        return SubagentRunStats(
            toolCalls = results.sumOf { it.stats.toolCalls },
            inputTokens = results.sumOf { it.stats.inputTokens },
            outputTokens = results.sumOf { it.stats.outputTokens },
            cacheWriteTokens = results.sumOf { it.stats.cacheWriteTokens },
            cacheReadTokens = results.sumOf { it.stats.cacheReadTokens },
            totalCost = results.sumOf { it.stats.totalCost },
            contextTokens = results.maxOfOrNull { it.stats.contextTokens } ?: 0,
            contextWindow = results.maxOfOrNull { it.stats.contextWindow } ?: 0,
            contextUsagePercentage = results.maxOfOrNull { it.stats.contextUsagePercentage } ?: 0.0
        )
    }

    // ---- Tool scoping ----

    /**
     * Resolve the tool subset appropriate for the given scope.
     * The `agent` tool is NEVER included in any scope (depth-1 enforcement).
     */
    internal fun resolveScopedTools(scope: String): Map<String, AgentTool> {
        val allTools = toolRegistry.allTools().associateBy { it.name }

        val allowed = when (scope) {
            "research" -> allTools.filterKeys { it in RESEARCH_TOOLS }
            "implement" -> allTools.filterKeys { it !in EXCLUDED_FROM_IMPLEMENT }
            "review" -> allTools.filterKeys { it in RESEARCH_TOOLS || it in REVIEW_EXTRA_TOOLS }
            else -> emptyMap()
        }

        return allowed
    }

    // ---- Sub-agent system prompt ----

    private fun buildSubAgentPrompt(scope: String): String = buildString {
        appendLine("You are a sub-agent working inside an IDE on project '${project.name}'.")
        appendLine("Project path: ${project.basePath}")
        appendLine()
        appendLine("## Your Role")
        when (scope) {
            "research" -> {
                appendLine("You are a RESEARCH agent. You can read files, search code, and analyze the codebase.")
                appendLine("You CANNOT edit files, create files, or run commands.")
            }
            "implement" -> {
                appendLine("You are an IMPLEMENTATION agent. You have full access to read, edit, create files, and run commands.")
                appendLine("You should implement the task completely, verify it works (compile, test), and report results.")
            }
            "review" -> {
                appendLine("You are a REVIEW agent. You can read files and run diagnostics/inspections.")
                appendLine("You CANNOT edit files or run commands. Report issues you find.")
            }
        }
        appendLine()
        appendLine("## Rules")
        appendLine("- You have NO access to any previous conversation. The task prompt is your ONLY input.")
        appendLine("- Work autonomously — you cannot ask questions or interact with the user.")
        appendLine("- When finished, call attempt_completion with a clear, structured summary of your findings/work.")
        appendLine("- Be thorough but efficient — you have a limited iteration budget.")
        appendLine("- NEVER respond without calling a tool. Always take action or call attempt_completion.")
        if (scope == "implement") {
            appendLine("- Read files before editing them.")
            appendLine("- After editing, verify your changes work (run tests, check compilation).")
            appendLine("- If tests fail, fix the issues before completing.")
        }
    }

    // ---- Helpers ----

    private fun errorResult(message: String) = ToolResult(
        content = message,
        summary = "agent error: $message",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    companion object {
        const val DEFAULT_CONTEXT_BUDGET = 150_000
        const val MAX_PARALLEL_PROMPTS = 5
        val PROMPT_KEYS = listOf("prompt", "prompt_2", "prompt_3", "prompt_4", "prompt_5")

        const val MIN_ITERATIONS = 5
        const val MAX_ITERATIONS = 100

        val VALID_SCOPES = setOf("research", "implement", "review")

        // ---- Research scope: read-only tools ----
        val RESEARCH_TOOLS = setOf(
            // Builtin read tools
            "read_file", "search_code", "glob_files", "think", "attempt_completion",
            "project_context", "current_time", "ask_questions",
            // PSI / code intelligence (all read-only)
            "find_definition", "find_references", "find_implementations",
            "file_structure", "type_hierarchy", "call_hierarchy",
            "type_inference", "get_method_body", "get_annotations",
            "read_write_access", "structural_search", "test_finder",
            "dataflow_analysis",
            // VCS read tools
            "git_status", "git_diff", "git_log", "git_blame",
            "git_show_file", "git_file_history", "git_show_commit",
            "git_branches", "changelist_shelve", "git_stash_list",
            "git_merge_base"
        )

        // ---- Review scope: research + diagnostics ----
        val REVIEW_EXTRA_TOOLS = setOf(
            "diagnostics", "run_inspections", "problem_view",
            "list_quickfixes", "coverage"
        )

        // ---- Implement scope: everything EXCEPT these ----
        val EXCLUDED_FROM_IMPLEMENT = setOf(
            "agent",                                        // Prevent recursion (CRITICAL)
            "jira", "bamboo_builds", "bamboo_plans",        // Integration tools
            "bitbucket_pr", "bitbucket_repo",
            "bitbucket_review", "sonar",
            "db_query", "db_schema", "db_list_profiles",    // Database tools
            "ask_user_input"                                // Sub-agents can't interact with user
        )

        /** Truncate text with ellipsis. */
        fun excerpt(text: String, maxChars: Int = 1200): String =
            if (text.length <= maxChars) text
            else text.take(maxChars) + "..."

        /** Format stats as a human-readable line. */
        fun formatStatsLine(stats: SubagentRunStats): String = buildString {
            append("Stats: ")
            append("${stats.toolCalls} tool calls")
            append(" | ${formatNumber(stats.inputTokens)} input tokens")
            append(" | ${formatNumber(stats.outputTokens)} output tokens")
            if (stats.contextWindow > 0) {
                append(" | context: ${formatNumber(stats.contextTokens)}/${formatNumber(stats.contextWindow)}")
                append(" (${String.format("%.0f", stats.contextUsagePercentage)}%)")
            }
        }

        /** Format a number with K suffix for readability. */
        fun formatNumber(n: Int): String = when {
            n >= 1_000_000 -> "${n / 1_000_000}.${(n % 1_000_000) / 100_000}M"
            n >= 1_000 -> "${n / 1_000}.${(n % 1_000) / 100}K"
            else -> n.toString()
        }
    }
}

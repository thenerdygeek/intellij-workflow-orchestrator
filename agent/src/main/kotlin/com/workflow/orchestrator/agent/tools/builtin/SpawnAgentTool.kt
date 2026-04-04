package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.core.ai.LlmBrain
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
 */
class SpawnAgentTool(
    private val brainProvider: suspend () -> LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project
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

        // 1. Fresh context (50K budget for sub-agents)
        val childContext = ContextManager(maxInputTokens = SUB_AGENT_CONTEXT_BUDGET)
        childContext.setSystemPrompt(buildSubAgentPrompt(scope))

        // 2. Scope-appropriate tools (agent tool NEVER included -- depth-1 enforcement)
        val scopedTools = resolveScopedTools(scope)

        // 3. Fresh brain
        val brain = brainProvider()

        // 4. Create and run child loop
        val clampedIterations = maxIter.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
        val childLoop = AgentLoop(
            brain = brain,
            tools = scopedTools,
            toolDefinitions = scopedTools.values.map { it.toToolDefinition() },
            contextManager = childContext,
            project = project,
            maxIterations = clampedIterations,
            planMode = scope != "implement"
        )

        val result = childLoop.run(prompt)

        return when (result) {
            is LoopResult.Completed -> ToolResult(
                content = "[Agent: $description]\n${result.summary}",
                summary = "Agent completed ($scope): ${result.summary.take(150)}",
                tokenEstimate = estimateTokens(result.summary),
                verifyCommand = result.verifyCommand
            )
            is LoopResult.Failed -> ToolResult(
                content = "[Agent: $description] Failed: ${result.error}",
                summary = "Agent failed after ${result.iterations} iterations: ${result.error.take(100)}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
            is LoopResult.Cancelled -> ToolResult(
                content = "[Agent: $description] Cancelled after ${result.iterations} iterations",
                summary = "Agent cancelled",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
            is LoopResult.PlanPresented -> ToolResult(
                content = "[Agent: $description] Plan:\n${result.plan}",
                summary = "Agent presented plan: ${result.plan.take(150)}",
                tokenEstimate = estimateTokens(result.plan)
            )
            is LoopResult.SessionHandoff -> ToolResult(
                content = "[Agent: $description] Session handoff requested. Context:\n${result.context.take(500)}",
                summary = "Agent requested session handoff after ${result.iterations} iterations",
                tokenEstimate = estimateTokens(result.context.take(500))
            )
        }
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
        const val SUB_AGENT_CONTEXT_BUDGET = 50_000
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
    }
}

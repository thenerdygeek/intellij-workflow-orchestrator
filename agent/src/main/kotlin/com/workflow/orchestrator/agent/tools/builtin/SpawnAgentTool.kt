package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.estimateTokens
import com.workflow.orchestrator.agent.ide.IdeContext
import com.workflow.orchestrator.agent.tools.subagent.AgentConfig
import com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoader
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified sub-agent delegation tool.
 *
 * All delegation goes through agent configs (bundled or user-defined).
 * The parent LLM specifies `agent_type` to pick a specialist, or omits it
 * to get the default `general-purpose` agent.
 *
 * Depth-1 hard limit: sub-agents CANNOT spawn further sub-agents.
 *
 * Read-only agents (no write tools) support parallel prompts for fan-out research.
 */
class SpawnAgentTool(
    private val brainProvider: suspend () -> LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project,
    var contextBudget: Int = DEFAULT_CONTEXT_BUDGET,
    var maxOutputTokens: Int? = null,
    var sessionDebugDir: java.io.File? = null,
    var toolExecutionMode: String = "accumulate",
    var onSubagentProgress: (suspend (String, SubagentProgressUpdate) -> Unit)? = null,
    private val configLoader: AgentConfigLoader? = null,
    private val ideContext: IdeContext? = null
) : AgentTool {

    override val name = "agent"

    override val description: String
        get() {
            val base = """Launch a focused sub-agent to handle a task in its own context window. Each sub-agent gets its own prompt and returns a comprehensive result. Use this for broad exploration when reading many files would consume your main context window, or to delegate self-contained implementation work. You do not need to launch multiple sub-agents every time — using one sub-agent is valid when it avoids unnecessary context usage for focused work.

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

Parallel execution (read-only agents only):
- For read-only agents (like "explorer"), you can provide up to 5 prompts (prompt, prompt_2, ..., prompt_5).
- Each prompt runs as a separate parallel subagent with its own context.
- Use this to fan out multiple research questions simultaneously.
- For agents with write tools, only the primary prompt is used (sequential).

Tips:
- Be specific in the prompt. "Fix the bug in UserService" is bad. "In src/main/kotlin/com/example/UserService.kt, the login() method at line 45 throws NPE when email is null. Add a null check and return an error result." is good.
- Include file paths. The sub-agent starts with zero context.
- For implementation tasks, tell the agent to verify its work (run tests, check compilation)."""

            val configs = configLoader?.getFilteredConfigs(ideContext)
            if (configs.isNullOrEmpty()) return base

            val suffix = buildString {
                appendLine()
                appendLine()
                appendLine("Available agent types (use with agent_type parameter):")
                for (config in configs.sortedBy { it.name }) {
                    appendLine("- \"${config.name}\": ${config.description}")
                }
            }
            return base + suffix.trimEnd()
        }

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
                description = "Optional second prompt (parallel execution, read-only agents only)."
            ),
            "prompt_3" to ParameterProperty(
                type = "string",
                description = "Optional third prompt (parallel execution, read-only agents only)."
            ),
            "prompt_4" to ParameterProperty(
                type = "string",
                description = "Optional fourth prompt (parallel execution, read-only agents only)."
            ),
            "prompt_5" to ParameterProperty(
                type = "string",
                description = "Optional fifth prompt (parallel execution, read-only agents only)."
            ),
            "agent_type" to ParameterProperty(
                type = "string",
                description = "Agent type to use. Defaults to 'general-purpose' if not specified. Each type has a curated system prompt and tool set."
            ),
            "max_iterations" to ParameterProperty(
                type = "integer",
                description = "Max iterations for the sub-agent (5-100, default 50). Lower = faster/cheaper."
            )
        ),
        required = listOf("description", "prompt")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER)
    override val timeoutMs: Long get() = Long.MAX_VALUE  // No timeout — bounded by iterations + budget

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val description = params["description"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: description")
        val prompt = params["prompt"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: prompt")
        val agentType = params["agent_type"]?.jsonPrimitive?.content ?: DEFAULT_AGENT_TYPE
        val maxIter = params["max_iterations"]?.jsonPrimitive?.intOrNull ?: 50

        val config = configLoader?.getCachedConfig(agentType)
            ?: return errorResult(buildUnknownAgentTypeError(agentType))

        val resolvedTools = resolveConfigTools(config)
        if (resolvedTools.isEmpty()) {
            return errorResult("Agent type '${config.name}' has no resolvable tools. Config lists: ${config.tools}")
        }

        val isReadOnly = inferPlanMode(resolvedTools)

        // Collect parallel prompts for read-only agents
        val prompts = if (isReadOnly) {
            PROMPT_KEYS.mapNotNull { key -> params[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }
        } else {
            listOf(prompt)
        }

        if (prompts.isEmpty()) {
            return errorResult("No valid prompts provided")
        }

        return if (prompts.size == 1) {
            executeSingle(description, prompts.first(), config, resolvedTools, isReadOnly, maxIter)
        } else {
            executeParallel(description, prompts, config, resolvedTools, maxIter)
        }
    }

    // ---- Running subagent registry (for cancellation) ----

    /** Registry of running subagent runners, keyed by agent ID. */
    private val runningAgents = ConcurrentHashMap<String, SubagentRunner>()

    /**
     * Cancel a running subagent by ID.
     * Called from the UI kill button via AgentController.
     * Returns true if the subagent was found and abort was requested; false if not found.
     */
    fun cancelAgent(agentId: String): Boolean {
        val runner = runningAgents[agentId] ?: return false
        runner.abort()
        LOG.info("[SpawnAgent] Abort requested for subagent $agentId")
        return true
    }

    // ---- Debug dir for sub-agents ----

    private val subagentCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Compute a unique debug dir for a sub-agent under the parent session's directory.
     * Layout: `sessions/{sid}/subagents/subagent-{N}-{sanitizedDesc}/`
     */
    private fun subagentDebugDir(description: String): java.io.File? {
        val parentDir = sessionDebugDir ?: return null
        val idx = subagentCounter.incrementAndGet()
        val safeName = description.take(40).replace(Regex("[^a-zA-Z0-9_-]"), "_").lowercase()
        return java.io.File(parentDir, "subagents/subagent-${idx}-${safeName}")
    }

    // ---- Config-based agent execution ----

    private fun resolveConfigTools(config: AgentConfig): Map<String, AgentTool> {
        val resolved = config.tools
            .filter { it != "agent" }  // Depth-1 enforcement
            .mapNotNull { name ->
                val tool = toolRegistry.get(name)
                if (tool == null) LOG.warn("[SpawnAgent] Config '${config.name}' references unknown tool: $name")
                tool?.let { name to it }
            }
            .toMap()
            .toMutableMap()
        // Always include attempt_completion — sub-agents MUST be able to terminate
        if ("attempt_completion" !in resolved) {
            toolRegistry.get("attempt_completion")?.let { resolved["attempt_completion"] = it }
        }
        return resolved
    }

    /**
     * Infer plan mode from tools: if no write tools are present, the agent is read-only.
     * Read-only agents run in plan mode (no file mutations) and support parallel execution.
     */
    private fun inferPlanMode(resolvedTools: Map<String, AgentTool>): Boolean {
        return resolvedTools.keys.none { it in AgentLoop.WRITE_TOOLS }
    }

    private fun buildUnknownAgentTypeError(name: String): String {
        val available = configLoader?.getFilteredConfigs(ideContext)
            ?.sortedBy { it.name }
            ?.joinToString(", ") { it.name }
            ?: "(none loaded)"
        return "Unknown agent type '$name'. Available: $available"
    }

    // ---- Single subagent execution ----

    private suspend fun executeSingle(
        description: String,
        prompt: String,
        config: AgentConfig,
        resolvedTools: Map<String, AgentTool>,
        planMode: Boolean,
        iterationOverride: Int
    ): ToolResult {
        val brain = brainProvider()
        // Scope the brain's XML parser to the subagent's tools, not the parent's.
        // Without this, the SourcegraphChatClient post-stream XML parser can't find
        // tool calls for tools not in the parent's active set (e.g., deferred tools
        // like file_structure), causing the response to be treated as text-only and
        // the LLM to hallucinate instead of executing tools.
        brain.toolNameSet = resolvedTools.keys
        brain.paramNameSet = resolvedTools.values.flatMap { it.parameters.properties.keys }.toSet()
        val maxIter = (config.maxTurns ?: iterationOverride).coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

        val runner = SubagentRunner(
            brain = brain,
            tools = resolvedTools,
            systemPrompt = config.systemPrompt,
            project = project,
            maxIterations = maxIter,
            planMode = planMode,
            contextBudget = contextBudget,
            maxOutputTokens = maxOutputTokens,
            apiDebugDir = subagentDebugDir(description),
            toolExecutionMode = toolExecutionMode
        )

        val agentId = generateAgentId()
        val uiLabel = "$description (${config.name})"
        runningAgents[agentId] = runner
        try {
            // Emit a single explicit "spawn" event with the label so the UI can render
            // exactly one card for this run. Subsequent progress events use the same
            // agentId — the UI dedupes on agentId.
            onSubagentProgress?.invoke(
                agentId,
                SubagentProgressUpdate(status = "running", label = uiLabel)
            )
            val result = runner.run(prompt) { progress ->
                // Don't re-emit "running" for per-tool ticks — that would re-spawn
                // the card. The runner only emits status="running" once at start;
                // we already emitted that above with the label, so suppress it here.
                val safe = if (progress.status == "running") progress.copy(status = null) else progress
                onSubagentProgress?.invoke(agentId, safe)
            }
            return mapSingleResult(description, config.name, result)
        } finally {
            runningAgents.remove(agentId)
        }
    }

    private fun mapSingleResult(
        description: String,
        label: String,
        result: SubagentRunResult
    ): ToolResult {
        val statsLine = formatStatsLine(result.stats)

        return when (result.status) {
            SubagentRunStatus.COMPLETED -> ToolResult(
                content = "[Agent: $description]\n${result.result ?: "(no output)"}\n\n$statsLine",
                summary = "Agent completed ($label): ${(result.result ?: "").take(150)}",
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

    // ---- Parallel subagent execution (read-only agents only) ----

    private suspend fun executeParallel(
        description: String,
        prompts: List<String>,
        config: AgentConfig,
        resolvedTools: Map<String, AgentTool>,
        iterationOverride: Int
    ): ToolResult {
        val maxIter = (config.maxTurns ?: iterationOverride).coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
        val uiLabel = "$description (${config.name})"

        // Create status entries for each prompt
        val entries = prompts.mapIndexed { idx, p ->
            SubagentStatusItem(
                index = idx,
                prompt = excerpt(p),
                status = "pending"
            )
        }

        // Run all prompts in parallel using supervisorScope.
        // Each child gets its OWN agentId (UUID) and emits its OWN spawn event,
        // so the UI renders one card per child with stable per-child dedupe.
        val results = supervisorScope {
            prompts.mapIndexed { idx, p ->
                async {
                    val brain = brainProvider()
                    // Scope brain's XML parser to subagent's tool set (same as executeSingle)
                    brain.toolNameSet = resolvedTools.keys
                    brain.paramNameSet = resolvedTools.values.flatMap { it.parameters.properties.keys }.toSet()
                    val runner = SubagentRunner(
                        brain = brain,
                        tools = resolvedTools,
                        systemPrompt = config.systemPrompt,
                        project = project,
                        maxIterations = maxIter,
                        planMode = true, // read-only agents are always plan mode
                        contextBudget = contextBudget,
                        maxOutputTokens = maxOutputTokens,
                        apiDebugDir = subagentDebugDir("${description}-${idx + 1}"),
                        toolExecutionMode = toolExecutionMode
                    )

                    val childAgentId = generateAgentId()
                    val childLabel = "${description} #${idx + 1} (${config.name})"
                    runningAgents[childAgentId] = runner

                    // Emit ONE explicit spawn event for this child. The UI dedupes
                    // on agentId, so subsequent progress events for the same id
                    // update the existing card instead of spawning new ones.
                    onSubagentProgress?.invoke(
                        childAgentId,
                        SubagentProgressUpdate(status = "running", label = childLabel)
                    )
                    entries[idx].status = "running"

                    try {
                        val result = runner.run(p) { progress ->
                            // Mirror progress into the entry for the group summary.
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

                            // Forward this update to the UI under the CHILD agentId.
                            // Suppress any "running" status from the runner — we already
                            // spawned the card explicitly above, and re-emitting "running"
                            // would re-spawn duplicate cards (the original 77-card bug).
                            val safe = if (progress.status == "running") progress.copy(status = null) else progress
                            onSubagentProgress?.invoke(childAgentId, safe)
                        }

                        // Final per-child status
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
                        result
                    } catch (e: Exception) {
                        entries[idx].status = "failed"
                        entries[idx].error = e.message ?: "Unknown error"
                        // Tell the UI this specific child failed.
                        onSubagentProgress?.invoke(
                            childAgentId,
                            SubagentProgressUpdate(status = "failed", error = e.message ?: "Unknown error")
                        )
                        SubagentRunResult(
                            status = SubagentRunStatus.FAILED,
                            error = e.message ?: "Unknown error"
                        )
                    } finally {
                        runningAgents.remove(childAgentId)
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

    // ---- Helpers ----

    private fun errorResult(message: String) = ToolResult(
        content = message,
        summary = "agent error: $message",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

    companion object {
        private val LOG = Logger.getInstance(SpawnAgentTool::class.java)

        const val DEFAULT_CONTEXT_BUDGET = 150_000
        const val MAX_PARALLEL_PROMPTS = 5
        const val DEFAULT_AGENT_TYPE = "general-purpose"
        val PROMPT_KEYS = listOf("prompt", "prompt_2", "prompt_3", "prompt_4", "prompt_5")

        const val MIN_ITERATIONS = 5
        const val MAX_ITERATIONS = 100

        /** Generate a short random ID for a subagent (8 hex chars). */
        fun generateAgentId(): String = java.util.UUID.randomUUID().toString().take(8)

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

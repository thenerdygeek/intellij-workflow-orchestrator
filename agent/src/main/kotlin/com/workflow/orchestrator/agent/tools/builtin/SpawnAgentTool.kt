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
import com.workflow.orchestrator.agent.tools.subagent.SubagentExecutionStatus
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunResult
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunStats
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunStatus
import com.workflow.orchestrator.agent.tools.subagent.SubagentRunner
import com.workflow.orchestrator.agent.tools.subagent.SubagentStatusItem
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.ModelCache
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
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
    private val brainProvider: suspend (modelOverride: String?) -> LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project,
    var contextBudget: Int = DEFAULT_CONTEXT_BUDGET,
    var maxOutputTokens: Int? = null,
    var sessionDebugDir: java.io.File? = null,
    var toolExecutionMode: String = "accumulate",
    var onSubagentProgress: (suspend (String, SubagentProgressUpdate) -> Unit)? = null,
    /**
     * Parent-session approval gate. Forwarded to every [SubagentRunner] so write tools
     * delegated to a sub-agent surface the same modal as write tools called directly
     * by the main agent. Closes the bypass where an orchestrator could delegate
     * `edit_file` / `run_command` to a sub-agent to avoid the approval UI.
     */
    var approvalGate: (suspend (toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean) -> com.workflow.orchestrator.agent.loop.ApprovalResult)? = null,
    /**
     * Parent-session approval store. Forwarded to every [SubagentRunner] so a sub-agent
     * honors "Allow for session" decisions the user has already made in this
     * conversation — without this, each sub-agent would start with an empty store and
     * re-prompt on every write tool, even when the parent already approved.
     * Shared reference, not a copy: approvals granted inside a sub-agent also propagate
     * back to the parent's subsequent turns.
     */
    var sessionApprovalStore: com.workflow.orchestrator.agent.loop.SessionApprovalStore? = null,
    /** Parent hook manager — fires PRE/POST_TOOL_USE etc. for sub-agent tool calls. */
    var hookManager: com.workflow.orchestrator.agent.hooks.HookManager? = null,
    /** Parent session metrics — sub-agent tool / API timings flow into parent scorecard. */
    var sessionMetrics: com.workflow.orchestrator.agent.observability.SessionMetrics? = null,
    /** Parent file logger — sub-agent lifecycle events land in the same JSONL stream. */
    var fileLogger: com.workflow.orchestrator.agent.observability.AgentFileLogger? = null,
    /** Parent debug-log callback — sub-agent warnings/events reach the JCEF debug panel. */
    var onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null,
    /** Parent checkpoint callback — fires after sub-agent write tools. */
    var onCheckpoint: (suspend () -> Unit)? = null,
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
- Pair each extra prompt with a matching description (description_2 for prompt_2, description_3 for prompt_3, etc.) so each worker gets a unique 3-5 word UI label. If a description_N is omitted, the primary description is reused.
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
            "description_2" to ParameterProperty(
                type = "string",
                description = "Optional 3-5 word label for prompt_2's worker card. Falls back to the primary description when omitted."
            ),
            "description_3" to ParameterProperty(
                type = "string",
                description = "Optional 3-5 word label for prompt_3's worker card. Falls back to the primary description when omitted."
            ),
            "description_4" to ParameterProperty(
                type = "string",
                description = "Optional 3-5 word label for prompt_4's worker card. Falls back to the primary description when omitted."
            ),
            "description_5" to ParameterProperty(
                type = "string",
                description = "Optional 3-5 word label for prompt_5's worker card. Falls back to the primary description when omitted."
            ),
            "agent_type" to ParameterProperty(
                type = "string",
                description = "Agent type to use. Defaults to 'general-purpose' if not specified. Each type has a curated system prompt and tool set."
            ),
            "model" to ParameterProperty(
                type = "string",
                description = "Optional model ID override for this subagent (e.g. 'anthropic::2024-10-22::claude-3-5-sonnet-latest'). Overrides the agent config's model and the default auto-selected model. Use when a specific model capability is required."
            ),
        ),
        required = listOf("description", "prompt")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER)
    override val timeoutMs: Long get() = Long.MAX_VALUE  // No timeout — bounded by iterations + budget

    override fun documentation(): ToolDocumentation = toolDoc("agent") {
        summary {
            technical(
                "Spawns one or more isolated sub-agent workers, each running a fresh ReAct loop with its own context window, system prompt, tool set, and iteration budget. " +
                "Resolves a persona config (bundled or user/project-defined), filters tools (drops `agent` for depth-1 enforcement, swaps `attempt_completion` → `task_report`), " +
                "then dispatches to either `executeSingle` (one worker, sequential) or `executeParallel` (2-5 workers via supervisorScope, read-only agents only). " +
                "Bounded by 200 iterations + context budget — there is no wall-clock timeout (`timeoutMs = Long.MAX_VALUE`)."
            )
            plain(
                "Like hiring a temp worker for a focused job: you write a one-page brief (the prompt), pick a specialist (agent_type), and they go off and do the work in their own room. " +
                "They can't see your conversation history, can't ask you questions mid-task, and report back once with a structured summary. For research tasks you can hire up to 5 temps in parallel."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without `agent`, the orchestrator must do all work itself: every file read, search, and edit lands in the parent context window. " +
            "Long-running research tasks would burn the parent's 150K input budget in 20-30 read_file calls; the only escape is `new_task` which loses all running state. " +
            "Parallel exploration disappears entirely (sequential serial loop), and persona specialization (security-auditor, spring-boot-engineer) collapses into the orchestrator's generic prompt. " +
            "Net effect: 2-3x faster context exhaustion, no fan-out, no skill-bounded delegation."
        )
        llmMistake(
            "Spawns a sub-agent for a single tool call (e.g. one `read_file` or `search_code`) — pays the full context-bootstrap + persona-prompt overhead (~3-5K tokens) for work that costs <500 tokens inline. " +
            "Description literally says \"don't over-delegate\" but the LLM ignores it ~10% of the time."
        )
        llmMistake(
            "Forgets the sub-agent has zero conversation history — writes prompts like \"continue what we were doing with the auth module\" instead of \"In src/auth/AuthService.kt, find all uses of `validateToken()` and report which call sites pass null\". " +
            "The sub-agent then either flails or bails out asking for context it can't get."
        )
        llmMistake(
            "Provides `prompt_2..prompt_5` to a coder/writer agent, expecting parallel execution — `inferPlanMode()` sees write tools, falls back to `executeSingle` with only `prompt`, and the extra prompts are silently discarded. " +
            "There is no error or warning in the result — the LLM thinks 5 workers ran, but only 1 did."
        )
        llmMistake(
            "Mismatches `description_N` ↔ `prompt_N` indices — e.g. provides description_2 + description_3 but only prompt_3 (skipping prompt_2). " +
            "Logic uses `mapIndexedNotNull` on PROMPT_KEYS, so prompt_3's worker label is taken from description_3 (correct), but the LLM sometimes assumes positional cascading and gets confused why labels appear shifted."
        )
        llmMistake(
            "Re-spawns a sub-agent with the same prompt after a transient failure instead of inspecting the structured `task_report` for partial progress — wastes a fresh iteration budget on work the previous worker may have nearly finished."
        )
        llmMistake(
            "Tries to invoke nonexistent `resume` / `kill` / `send` actions described in older docs — these are not parameters on this tool. " +
            "Cancellation is UI-driven (the user clicks Kill in the worker card, which calls `cancelAgent(agentId)`); there is no LLM-callable kill or resume."
        )
        actions {
            action("single") {
                description {
                    technical(
                        "Spawns one sub-agent via `executeSingle`. The parent LLM provides `description` + `prompt`, optionally an `agent_type` (defaults to `general-purpose`) and `model` override. " +
                        "Suspends the parent loop until the worker completes (COMPLETED) or fails (FAILED), then returns a single `ToolResult` containing `[Agent: <desc>] <result>` plus a stats line."
                    )
                    plain(
                        "The standard \"go do this thing for me\" call. One worker, one prompt, one result. The parent agent waits for it to finish, then sees the report."
                    )
                }
                whenLLMUses(
                    "Default mode for any focused delegation: implementation work (\"refactor X\"), coder/reviewer specialist tasks, single-question research, anything that doesn't need fan-out. " +
                    "Also the only mode available for personas with write tools — `inferPlanMode()` forces single execution when any tool in the resolved set is in `AgentLoop.WRITE_TOOLS`."
                )
                params {
                    required("description", "string") {
                        llmSeesIt("Short 3-5 word description of what the agent will do (e.g., 'Fix null check in UserService')")
                        humanReadable("The label that appears on the worker card in the parent's UI — like the title of a sticky note on the temp worker's desk.")
                        whenPresent("Used as the worker card title and prepended to the result content as `[Agent: <description>]`.")
                        constraint("non-empty; trimmed for the file-system debug dir name (max 40 chars, alphanumerics + `_`/`-` only)")
                        example("Find all uses of validateToken")
                        example("Refactor AuthService to use Result")
                    }
                    required("prompt", "string") {
                        llmSeesIt("Complete task description. Include ALL context the agent needs — file paths, class names, what to look for or change, and why. The agent has NO access to your conversation history.")
                        humanReadable("The full one-page brief the worker will read before starting. They can't see your conversation, so spell out file paths, class names, expected outcomes, and how to verify.")
                        whenPresent("Becomes the FIRST user message in the sub-agent's fresh ReAct loop. Token budget consumed counts against the sub-agent's context, not the parent's.")
                        constraint("non-empty; no upper length cap, but counts against sub-agent's context budget")
                        example("In src/main/kotlin/com/example/UserService.kt, the login() method at line 45 throws NPE when email is null. Add a null check returning a Result.failure with a clear message, then run `./gradlew :user:test --tests UserServiceTest`.")
                    }
                    optional("agent_type", "string") {
                        llmSeesIt("Agent type to use. Defaults to 'general-purpose' if not specified. Each type has a curated system prompt and tool set.")
                        humanReadable("Which specialist persona to hire — `code-reviewer`, `spring-boot-engineer`, `security-auditor`, `explorer`, etc. Each is a markdown file with a system prompt + curated tool list.")
                        whenPresent("Resolved via `configLoader.getCachedConfig(agentType)`. If unknown, returns an error listing all filtered configs (filtered by `IdeContext` so PyCharm doesn't see `spring-boot-engineer`).")
                        whenAbsent("Defaults to `DEFAULT_AGENT_TYPE = \"general-purpose\"`.")
                        constraint("must match a config name from `~/.workflow-orchestrator/agents/`, `.workflow/agents/`, or the bundled `agent/src/main/resources/agents/` set, AFTER IdeContext filtering")
                        example("general-purpose")
                        example("code-reviewer")
                        example("spring-boot-engineer")
                        example("security-auditor")
                    }
                    optional("model", "string") {
                        llmSeesIt("Optional model ID override for this subagent (e.g. 'anthropic::2024-10-22::claude-3-5-sonnet-latest'). Overrides the agent config's model and the default auto-selected model. Use when a specific model capability is required.")
                        humanReadable("Force a specific LLM for this sub-agent — overrides the persona's `model:` frontmatter and the global Sonnet default.")
                        whenPresent("Passed to `brainProvider(modelOverride)`; the sub-agent's brain reports this as `brain.modelId` and the worker card shows it.")
                        whenAbsent("Falls back through: persona's `config.modelId` → `ModelCache.pickSonnetNonThinking()` → orchestrator's auto-selected model. Sub-agent default tier is Sonnet non-thinking, NOT the orchestrator's tier.")
                        example("anthropic::2024-10-22::claude-3-5-sonnet-latest")
                    }
                }
                rejectsParam("prompt_2", "`single` mode only reads `prompt`. `prompt_2..5` are silently ignored unless the resolved persona has zero write tools (then the call routes to `parallel_fanout` instead).")
                rejectsParam("prompt_3", "Same as prompt_2 — single mode ignores it.")
                rejectsParam("prompt_4", "Same as prompt_2 — single mode ignores it.")
                rejectsParam("prompt_5", "Same as prompt_2 — single mode ignores it.")
                rejectsParam("description_2", "Per-worker labels only matter in `parallel_fanout` mode.")
                rejectsParam("description_3", "Per-worker labels only matter in `parallel_fanout` mode.")
                rejectsParam("description_4", "Per-worker labels only matter in `parallel_fanout` mode.")
                rejectsParam("description_5", "Per-worker labels only matter in `parallel_fanout` mode.")
                precondition("`configLoader` must have the requested `agent_type` cached (or default `general-purpose` must exist) — unknown types return an error listing available configs.")
                precondition("Resolved persona must have at least one resolvable core tool — `attempt_completion` and `agent` are filtered out, and `task_report` is auto-injected. If the resulting set is empty, returns error.")
                precondition("Caller must be `WorkerType.ORCHESTRATOR` or `WorkerType.CODER` (sub-agents cannot spawn further sub-agents — depth-1 hard limit).")
                onSuccess(
                    "Returns a single `ToolResult` with content `[Agent: <description>]\\n<result>\\n\\nStats: <tool calls> | <input tokens> | <output tokens> | context: used/window (XX%)`. " +
                    "`completionData` is null (sub-agents don't surface artifacts to the parent UI). " +
                    "Side effects: file ownership entries acquired during the run are released; running-agent registry entry is cleaned up; debug dir at `sessions/{sid}/subagents/subagent-{N}-{slug}/` is preserved."
                )
                onFailure(
                    "agent_type unknown",
                    "Returns error: `Unknown agent type 'X'. Available: <list>`. LLM should re-call with a valid name (likely typo or missing IdeContext filtering). The available list is post-IdeContext-filter, so PyCharm sessions won't see `spring-boot-engineer` even if it exists."
                )
                onFailure(
                    "missing description or prompt",
                    "Returns `Missing required parameter: description` or `Missing required parameter: prompt`. LLM must supply both — neither has a default."
                )
                onFailure(
                    "config has no resolvable core tools",
                    "Returns `Agent type '<n>' has no resolvable core tools. Config lists: [...]`. Indicates the persona's YAML `tools:` field references unregistered tool names — usually a stale config referencing a renamed/dropped tool."
                )
                onFailure(
                    "sub-agent COMPLETED with empty `task_report` (no result)",
                    "Returns `[Agent: <desc>]\\n(no output)\\n\\nStats: ...` with `isError = false`. The sub-agent technically finished but produced no usable output — usually means it hit max iterations without calling `task_report`. LLM should re-prompt with tighter scope."
                )
                onFailure(
                    "sub-agent FAILED (uncaught exception, brain error, max iterations)",
                    "Returns `[Agent: <desc>] Failed: <error>\\n\\nStats: ...` with `isError = true`. Common causes: NETWORK_ERROR from LLM provider, context overflow that compaction couldn't recover from, sub-agent loop detected and aborted."
                )
                onFailure(
                    "user clicks Kill in worker UI card",
                    "`cancelAgent(agentId)` calls `runner.abort()`. Sub-agent loop exits at next iteration boundary; result is FAILED with `error = \"aborted by user\"` and `isError = true`. Parent loop receives a normal tool result and continues."
                )
                example("delegate refactor work to coder persona") {
                    param("description", "Refactor AuthService Result")
                    param("prompt", "In agent/src/main/kotlin/com/example/AuthService.kt, refactor the login() method to return Result<User, AuthError> instead of throwing. Update all call sites (search via search_code for AuthService.login). Run `./gradlew :agent:test --tests AuthServiceTest` to verify. Return a list of files changed and any test failures.")
                    param("agent_type", "general-purpose")
                    outcome("Sub-agent runs in its own context, edits files (acquiring file ownership), runs tests, calls task_report. Parent receives a structured summary in the next iteration.")
                    notes("Write-capable persona — even though `prompt_2..5` could be added, `inferPlanMode()` returns false because edit_file/run_command are write tools, so parallel mode is suppressed.")
                }
                example("specialist persona for security audit") {
                    param("description", "Audit auth module CSRF")
                    param("prompt", "Audit agent/src/main/kotlin/com/example/auth/ for CSRF vulnerabilities. Check every controller endpoint for missing @CSRF tokens, every form-handling method, and the session middleware. Report findings as: file:line — vulnerability — recommended fix. Do not modify any code.")
                    param("agent_type", "security-auditor")
                    outcome("security-auditor persona loads with its curated read-only tool set + project-scoped memory injection. Returns structured findings; no file mutations.")
                }
                example("model override for vision-needed task") {
                    param("description", "OCR diagrams in screenshots")
                    param("prompt", "Read the three architecture screenshots in attachments/ and describe each in 2 paragraphs. Look for component names, arrows, and labels.")
                    param("agent_type", "general-purpose")
                    param("model", "anthropic::2024-10-22::claude-3-5-sonnet-latest")
                    outcome("Sub-agent uses the explicit model regardless of persona default — useful when the default is non-vision and the prompt has images. (Note: the actual image-bearing routing happens at BrainRouter, not here.)")
                }
                verdict {
                    keep(
                        "Architecturally load-bearing. Without single-mode delegation the orchestrator must do all work itself, exhausting its context window 2-3x faster on any non-trivial task. " +
                        "Sub-agents also unlock the persona system (8 bundled + user customs) and fresh-context exploration without polluting the parent's history.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("parallel_fanout") {
                description {
                    technical(
                        "Spawns 2-5 sub-agents concurrently via `executeParallel` with `supervisorScope { prompts.mapIndexed { ... async { runner.run(p) } } }`. " +
                        "Each child gets its own UUID `agentId`, its own debug dir, and emits its own spawn event so the UI renders one card per child. " +
                        "All children share the same persona config and tool set. Activated when `inferPlanMode()` returns true (no write tools in resolved set) AND at least one of `prompt_2..5` is non-blank. " +
                        "Aggregates stats with `sumOf` for tool calls + tokens + cost, and `maxOfOrNull` for context-window fields (since each child has its own window)."
                    )
                    plain(
                        "Hire 2-5 temps in parallel for related research questions. They each get a different prompt, work simultaneously, and you get one combined report at the end. Only works for read-only specialists like `explorer` — anyone who can edit files runs alone."
                    )
                }
                whenLLMUses(
                    "Fan-out research: \"in parallel, find all uses of X, find all callers of Y, find the test coverage for Z\". " +
                    "Multi-perspective review: \"reviewer-1 checks security, reviewer-2 checks performance, reviewer-3 checks style\". " +
                    "Any time the LLM has 2+ independent questions whose answers don't depend on each other and a read-only persona is appropriate."
                )
                params {
                    required("description", "string") {
                        llmSeesIt("Short 3-5 word description of what the agent will do (e.g., 'Fix null check in UserService')")
                        humanReadable("Group label — used as the fallback per-worker label when `description_N` is omitted, and as the parent-side group title in the UI.")
                        whenPresent("Becomes the prefix for every worker card label: `<description> #<idx> (<configName>)` unless overridden by `description_N`.")
                        example("Find auth-module bugs")
                    }
                    required("prompt", "string") {
                        llmSeesIt("Complete task description. Include ALL context the agent needs — file paths, class names, what to look for or change, and why. The agent has NO access to your conversation history.")
                        humanReadable("The first prompt — first sub-agent's task brief. Same rules as single mode: spell out file paths, class names, expected return shape.")
                        whenPresent("Worker #1's prompt. Worker #1's label uses the primary `description`.")
                        example("Search for all uses of validateToken() in agent/src and report file:line for each.")
                    }
                    optional("prompt_2", "string") {
                        llmSeesIt("Optional second prompt (parallel execution, read-only agents only).")
                        humanReadable("Second worker's task brief. Independent from prompt_1.")
                        whenPresent("Spawns worker #2 in parallel. If `description_2` is also present, that becomes the worker's label; otherwise the primary description is reused.")
                        whenAbsent("Worker #2 is not spawned. If `prompt_3..5` are also absent, falls back to single mode.")
                    }
                    optional("prompt_3", "string") {
                        llmSeesIt("Optional third prompt (parallel execution, read-only agents only).")
                        humanReadable("Third worker's task brief.")
                        whenPresent("Spawns worker #3 in parallel.")
                        whenAbsent("Worker #3 is not spawned.")
                    }
                    optional("prompt_4", "string") {
                        llmSeesIt("Optional fourth prompt (parallel execution, read-only agents only).")
                        humanReadable("Fourth worker's task brief.")
                        whenPresent("Spawns worker #4 in parallel.")
                        whenAbsent("Worker #4 is not spawned.")
                    }
                    optional("prompt_5", "string") {
                        llmSeesIt("Optional fifth prompt (parallel execution, read-only agents only).")
                        humanReadable("Fifth worker's task brief — hard cap at 5.")
                        whenPresent("Spawns worker #5 in parallel.")
                        whenAbsent("Worker #5 is not spawned.")
                    }
                    optional("description_2", "string") {
                        llmSeesIt("Optional 3-5 word label for prompt_2's worker card. Falls back to the primary description when omitted.")
                        humanReadable("Per-worker label — only affects the UI card title, not the prompt.")
                        whenPresent("Worker #2's card shows `<description_2> #2 (<configName>)`.")
                        whenAbsent("Worker #2's card shows `<description> #2 (<configName>)` (uses primary).")
                    }
                    optional("description_3", "string") {
                        llmSeesIt("Optional 3-5 word label for prompt_3's worker card. Falls back to the primary description when omitted.")
                        humanReadable("Per-worker label for worker #3.")
                        whenPresent("Worker #3's card uses this label.")
                        whenAbsent("Worker #3's card falls back to primary description.")
                    }
                    optional("description_4", "string") {
                        llmSeesIt("Optional 3-5 word label for prompt_4's worker card. Falls back to the primary description when omitted.")
                        humanReadable("Per-worker label for worker #4.")
                        whenPresent("Worker #4's card uses this label.")
                        whenAbsent("Worker #4's card falls back to primary description.")
                    }
                    optional("description_5", "string") {
                        llmSeesIt("Optional 3-5 word label for prompt_5's worker card. Falls back to the primary description when omitted.")
                        humanReadable("Per-worker label for worker #5.")
                        whenPresent("Worker #5's card uses this label.")
                        whenAbsent("Worker #5's card falls back to primary description.")
                    }
                    optional("agent_type", "string") {
                        llmSeesIt("Agent type to use. Defaults to 'general-purpose' if not specified. Each type has a curated system prompt and tool set.")
                        humanReadable("Specialist persona — must be read-only (no write tools) for parallel mode to engage.")
                        whenPresent("Resolved + tool-filtered. If `inferPlanMode()` is false (any write tool in resolved set), parallel mode is silently downgraded to single mode and `prompt_2..5` are ignored.")
                        whenAbsent("Defaults to `general-purpose` — which IS read-only by default in the bundled config.")
                        example("explorer")
                        example("general-purpose")
                    }
                    optional("model", "string") {
                        llmSeesIt("Optional model ID override for this subagent (e.g. 'anthropic::2024-10-22::claude-3-5-sonnet-latest'). Overrides the agent config's model and the default auto-selected model. Use when a specific model capability is required.")
                        humanReadable("Same as single mode — overrides every parallel worker's model.")
                        whenPresent("All N parallel workers use this model.")
                        whenAbsent("All workers use the persona/Sonnet-default fallback chain.")
                    }
                }
                precondition("Resolved persona must have ZERO tools in `AgentLoop.WRITE_TOOLS` — `inferPlanMode()` returns false otherwise and the call silently falls back to single mode.")
                precondition("At least one of `prompt_2..prompt_5` must be non-blank for parallel mode to actually engage; otherwise `executeSingle` is called.")
                precondition("Hard cap of 5 prompts (`MAX_PARALLEL_PROMPTS = 5`) — extra slots in the schema would compete with other tools' description budget.")
                onSuccess(
                    "Returns one `ToolResult` aggregating all children: header `[Parallel agents: <description>]\\nTotal: N | Succeeded: S | Failed: F`, then per-worker excerpts (1200-char cap each), then aggregate stats line. " +
                    "`isError = true` ONLY if ALL workers failed (`hasErrors && succeeded == 0`); partial failures still return success so the LLM can use the partial results. " +
                    "Stats: `toolCalls/inputTokens/outputTokens/totalCost` summed across children; `contextTokens/contextWindow/contextUsagePercentage` are the MAX across children (not sum, since each has its own window)."
                )
                onFailure(
                    "agent_type has write tools",
                    "Silent fallback to single mode — only `prompt` is honored, `prompt_2..5` are dropped. No warning in the result. " +
                    "Mitigation: the description does say \"For agents with write tools, only the primary prompt is used (sequential)\" but the LLM doesn't always read it."
                )
                onFailure(
                    "one or more workers fail (FAILED status)",
                    "Per-worker FAILED entries are included in the aggregate output: `--- Agent N: <prompt excerpt> --- FAILED: <error>`. Aggregate result is success unless ALL N failed."
                )
                onFailure(
                    "exception thrown inside a worker (caught in `try { ... } catch (e: Exception)`)",
                    "Worker entry's status set to FAILED, error = exception message. Other workers continue (supervisorScope semantics). Aggregate stats include this worker's partial counters."
                )
                onFailure(
                    "supervisorScope cancelled (parent agent aborts)",
                    "Each child receives cancellation; their `runner.run` exits via CancellationException; `runningAgents.remove(childAgentId)` runs in `finally`. Aggregate result is the partial set of completed children."
                )
                example("3-way parallel exploration with explorer agent") {
                    param("description", "Audit auth module")
                    param("prompt", "Find all controllers in agent/src/main/kotlin/com/example/auth/ and list their @RequestMapping paths.")
                    param("prompt_2", "Find all session-handling code in agent/src/main/kotlin/com/example/auth/ and report which methods read/write the session.")
                    param("prompt_3", "Find all auth-related test files in agent/src/test/ and report what scenarios are covered.")
                    param("description_2", "Map session methods")
                    param("description_3", "Audit auth tests")
                    param("agent_type", "explorer")
                    outcome("3 explorer workers run concurrently. Aggregate result has 3 sections, one per prompt. Total tool-calls + tokens summed; max context usage reported.")
                    notes("explorer is read-only — `inferPlanMode()` returns true, parallel mode engages.")
                }
                example("4-way review with custom reviewers") {
                    param("description", "Review PR 123")
                    param("prompt", "Check PR 123's diff for security issues.")
                    param("prompt_2", "Check PR 123's diff for performance regressions.")
                    param("prompt_3", "Check PR 123's diff for test coverage gaps.")
                    param("prompt_4", "Check PR 123's diff for style/naming consistency with the existing codebase.")
                    param("agent_type", "code-reviewer")
                    outcome("4 code-reviewer workers run concurrently with 4 perspectives. If any individual review fails, the others still return; result aggregates all 4.")
                }
                verdict {
                    keep(
                        "5x throughput on read-only research/review fan-out is unique to this mode — sequential delegation cannot match it. " +
                        "Cap of 5 keeps schema cost bounded (10 extra params total: prompt_2..5 + description_2..5).",
                        VerdictSeverity.STRONG
                    )
                    drop(
                        "10 schema slots (prompt_2..5 + description_2..5) is a meaningful chunk of the agent tool's description token budget. If usage data shows fan-out is rare (<5% of agent calls), consider replacing with a separate `agent_parallel` tool that's deferred-tier and only loaded when needed.",
                        VerdictSeverity.WEAK
                    )
                }
            }
        }
        flowchart("""
            flowchart TD
                A[LLM calls agent] --> B{description + prompt present?}
                B -- no --> Z1[error: missing required param]
                B -- yes --> C[configLoader.getCachedConfig agent_type]
                C -- not found --> Z2[error: unknown agent_type, list available]
                C -- found --> D[resolveConfigToolsTiered<br/>filters out 'agent' + 'attempt_completion'<br/>injects 'task_report']
                D --> E{coreTools.isEmpty?}
                E -- yes --> Z3[error: no resolvable core tools]
                E -- no --> F[inferPlanMode<br/>= no WRITE_TOOLS in set]
                F --> G{readOnly AND any prompt_2..5?}
                G -- yes --> H[executeParallel<br/>supervisorScope mapIndexed async]
                G -- no --> I[executeSingle]
                H --> J[N children run concurrently<br/>each with own agentId, own debug dir]
                I --> K[1 child runs sequentially]
                J --> L[aggregateStats: sum + max-by-window]
                K --> M[mapSingleResult]
                L --> N[ToolResult content + isError if all failed]
                M --> N
        """)
        verdict {
            keep(
                "The architectural keystone for context management at scale: without sub-agent delegation, the orchestrator's 150K input window is the only context budget the entire session has. " +
                "8 bundled personas + custom user/project agents + 5x parallel fan-out makes this the single highest-leverage tool in the registry.",
                VerdictSeverity.STRONG
            )
        }
        observation(
            "Historical note: a phantom 5-action LLM API (`spawn`/`resume`/`kill`/`send`/`run_in_background`) " +
            "was previously described in CLAUDE.md and never existed in source. CLAUDE.md was reconciled in " +
            "commit 7eb703cca to reflect the actual schema: only `description`, `prompt`, `prompt_2..5`, " +
            "`agent_type`, and `model` are LLM-callable. `cancelAgent(agentId)` exists in `AgentController` " +
            "but is wired exclusively to the UI Kill button — no equivalent LLM tool path."
        )
        observation(
            "5 prompt slots inflates the schema by ~10 properties (prompt_2..5 + description_2..5) on a Core tool. If telemetry shows parallel mode is used <5% of the time, this is meaningful budget for a rare path. " +
            "Alternative: split into two tools (`agent` for single, `agent_parallel` deferred-tier) — but that breaks the unified mental model."
        )
        mergeOpportunity(
            "Could fold `description_2..5` into a JSON `prompts` array param: `prompts: [{prompt, description}, ...]`. " +
            "Saves 5 schema slots but breaks the LLM's pattern-matching on `prompt_N`/`description_N` parallel keys. " +
            "Net win unclear — the current shape is verbose but easy for the LLM to fill correctly."
        )
        observation(
            "8 bundled specialist personas + 5 built-in types + unbounded user/project customs = a LOT of complexity in one tool. The LLM's `agent_type` selection accuracy depends on description quality of every persona, " +
            "and the IdeContext filter is the only thing keeping the suffix list from blowing up. Worth measuring per-persona usage to see which 2-3 carry their weight."
        )
        observation(
            "No wall-clock timeout (`timeoutMs = Long.MAX_VALUE`) is intentional but means a stuck sub-agent only stops via iteration cap (200) or context budget (150K). " +
            "A pathologically slow LLM provider could keep a worker pinned for hours. Mitigation: user clicks Kill; or the parent's overall session is cancelled."
        )
        related(
            "task_report",
            Relationship.COMPOSE_WITH,
            "Sub-agents call `task_report` instead of `attempt_completion`. `resolveConfigToolsTiered()` auto-injects `task_report` into every persona's tool set and silently drops any `attempt_completion` references."
        )
        related(
            "new_task",
            Relationship.ALTERNATIVE,
            "Use `new_task` for full session HANDOFF (one-way, fresh top-level session, parent loop ends). Use `agent` for DELEGATION (parent waits for result, parent loop continues). Different mental models — handoff vs subroutine call."
        )
        related(
            "attempt_completion",
            Relationship.ALTERNATIVE,
            "Orchestrator-only completion signal — sub-agents are explicitly forbidden from calling it (`allowedWorkers = {ORCHESTRATOR}`) and `resolveConfigToolsTiered` filters it out of every persona config. The mirror-image of `task_report` at the orchestrator boundary."
        )
        related(
            "tool_search",
            Relationship.SEE_ALSO,
            "Personas can request deferred tools via their `deferredTools:` YAML field, which `resolveConfigToolsTiered` resolves alongside core tools — different from `tool_search`'s runtime activation but achieves a similar end."
        )
        downside(
            "File ownership is enforced via `FileOwnershipRegistry` at WHOLE-FILE granularity. Two parallel sub-agents that need to edit different methods in the same file will conflict — the second worker waits or errors. " +
            "Read tools only WARN about ownership, so a parallel reviewer can read what a writer is mid-edit (potentially seeing partial state)."
        )
        downside(
            "Parent loses real-time visibility into in-flight sub-agent work — only the final `task_report` flows into the parent's context. The UI shows worker cards with progress, but the LLM only sees the result. " +
            "Means the LLM can't course-correct mid-run; if the sub-agent goes off the rails, the parent doesn't know until 200 iterations later."
        )
        downside(
            "WorkerMessageBus uses `Channel(20, DROP_OLDEST)` — message bursts above 20 silently lose the oldest messages. Inter-agent communication is best-effort, not durable."
        )
        downside(
            "Sub-agent default model is hard-coded to `pickSonnetNonThinking` — if the orchestrator runs on Opus, sub-agents drop a tier by default. Persona `model:` frontmatter overrides this, but most bundled personas don't set it."
        )
        downside(
            "Silent fallback when `prompt_2..5` are sent to a write-capable persona is a UX trap. The LLM thinks it spawned 5 workers; only 1 ran. No warning surfaces in the result content."
        )
        downside(
            "`subagentDebugDir` filename collision: the per-call counter is shared across single + parallel calls within the same parent session. Rapid-fire parallel calls produce sequential `subagent-N-<slug>` dirs, but the slug is truncated to 40 chars + sanitized — two calls with similar descriptions can share a dir if filename normalization collapses them (rare but observable on long descriptions)."
        )
        narrative("agent")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val description = params["description"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: description")
        val prompt = params["prompt"]?.jsonPrimitive?.content
            ?: return errorResult("Missing required parameter: prompt")
        val agentType = params["agent_type"]?.jsonPrimitive?.content ?: DEFAULT_AGENT_TYPE
        // model param > agent config's modelId > auto-selected default
        val modelOverride = params["model"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val config = configLoader?.getCachedConfig(agentType)
            ?: return errorResult(buildUnknownAgentTypeError(agentType))

        val (coreTools, deferredToolsForConfig) = resolveConfigToolsTiered(config)
        if (coreTools.isEmpty()) {
            return errorResult("Agent type '${config.name}' has no resolvable core tools. Config lists: ${config.tools}")
        }

        val isReadOnly = inferPlanMode(coreTools)

        // Collect parallel prompts for read-only agents, paired with their per-worker descriptions.
        // Alignment: PROMPT_KEYS[i] ↔ DESCRIPTION_KEYS[i]. Missing description_N falls back to the primary description.
        val promptPairs: List<Pair<String, String>> = if (isReadOnly) {
            PROMPT_KEYS.mapIndexedNotNull { i, pKey ->
                val p = params[pKey]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val dKey = DESCRIPTION_KEYS[i]
                val d = params[dKey]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: description
                p to d
            }
        } else {
            listOf(prompt to description)
        }

        if (promptPairs.isEmpty()) {
            return errorResult("No valid prompts provided")
        }

        // Effective model: explicit param > YAML frontmatter > Sonnet non-thinking (sub-agent default tier)
        // > orchestrator's auto-selected model (when no Sonnet is available on the instance).
        val subagentDefaultModel = ModelCache.pickSonnetNonThinking(ModelCache.getCached())?.id
        val effectiveModelOverride = modelOverride ?: config.modelId ?: subagentDefaultModel

        return if (promptPairs.size == 1) {
            executeSingle(description, promptPairs.first().first, config, coreTools, deferredToolsForConfig, isReadOnly, effectiveModelOverride)
        } else {
            executeParallel(description, promptPairs, config, coreTools, deferredToolsForConfig, effectiveModelOverride)
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

    /**
     * Resolve config.tools into the core map and config.deferredTools into a
     * (tool, category) map. `agent` is filtered from both (depth-1 enforcement).
     * `attempt_completion` is always injected into core.
     *
     * Returns: Pair(coreTools, deferredTools)
     * where deferredTools values are Pair(AgentTool, category: String)
     */
    internal fun resolveConfigToolsTiered(
        config: AgentConfig
    ): Pair<Map<String, AgentTool>, Map<String, Pair<AgentTool, String>>> {
        // --- Core ---
        // Filter out "agent" (recursion guard) and "attempt_completion" (orchestrator-only;
        // sub-agents receive task_report instead). Some persona configs list attempt_completion
        // in their tools field — silently drop it here so the LLM isn't confused by both.
        val core = config.tools
            .filter { it != "agent" && it != "attempt_completion" }
            .mapNotNull { name ->
                val tool = toolRegistry.get(name)
                if (tool == null) LOG.warn("[SpawnAgent] Config '${config.name}' references unknown core tool: $name")
                tool?.let { name to it }
            }
            .toMap()
            .toMutableMap()

        // Sub-agents use task_report instead of attempt_completion.
        // attempt_completion stays orchestrator-only (its allowedWorkers enforces this at schema time).
        if ("task_report" !in core) {
            toolRegistry.get("task_report")?.let { core["task_report"] = it }
        }

        // --- Deferred ---
        val deferred = config.deferredTools
            .filter { it != "agent" && it !in core }
            .mapNotNull { name ->
                val tool = toolRegistry.get(name)
                if (tool == null) LOG.warn("[SpawnAgent] Config '${config.name}' references unknown deferred tool: $name")
                tool?.let {
                    val category = toolRegistry.getDeferredCategory(name)
                    name to (it to category)
                }
            }
            .toMap()

        return core to deferred
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
        coreTools: Map<String, AgentTool>,
        deferredTools: Map<String, Pair<AgentTool, String>>,
        planMode: Boolean,
        modelOverride: String? = null
    ): ToolResult {
        val brain = brainProvider(modelOverride)
        // Scope the brain's XML parser to the subagent's tools, not the parent's.
        // Without this, the SourcegraphChatClient post-stream XML parser can't find
        // tool calls for tools not in the parent's active set (e.g., deferred tools
        // like file_structure), causing the response to be treated as text-only and
        // the LLM to hallucinate instead of executing tools.
        brain.toolNameSet = coreTools.keys
        brain.paramNameSet = coreTools.values.flatMap { it.parameters.properties.keys }.toSet()

        val runner = SubagentRunner(
            brain = brain,
            coreTools = coreTools,
            deferredTools = deferredTools,
            systemPrompt = config.systemPrompt,
            project = project,
            maxIterations = DEFAULT_MAX_ITERATIONS,
            planMode = planMode,
            contextBudget = contextBudget,
            maxOutputTokens = maxOutputTokens,
            apiDebugDir = subagentDebugDir(description),
            toolExecutionMode = toolExecutionMode,
            approvalGate = approvalGate,
            sessionApprovalStore = sessionApprovalStore,
            hookManager = hookManager,
            sessionMetrics = sessionMetrics,
            fileLogger = fileLogger,
            onDebugLog = onDebugLog,
            onCheckpoint = onCheckpoint,
            ideContext = ideContext,
            agentConfig = config,
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
                SubagentProgressUpdate(
                    status = SubagentExecutionStatus.RUNNING,
                    label = uiLabel,
                    model = brain.modelId,
                )
            )
            val result = runner.run(prompt) { progress ->
                // Don't re-emit "running" for per-tool ticks — that would re-spawn
                // the card. The runner only emits status="running" once at start;
                // we already emitted that above with the label, so suppress it here.
                val safe = if (progress.status == SubagentExecutionStatus.RUNNING) progress.copy(status = null) else progress
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
                completionData = null
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
        promptPairs: List<Pair<String, String>>,
        config: AgentConfig,
        coreTools: Map<String, AgentTool>,
        deferredTools: Map<String, Pair<AgentTool, String>>,
        modelOverride: String? = null
    ): ToolResult {
        val maxIter = DEFAULT_MAX_ITERATIONS
        val uiLabel = "$description (${config.name})"
        val prompts = promptPairs.map { it.first }

        // Create status entries for each prompt
        val entries = prompts.mapIndexed { idx, p ->
            SubagentStatusItem(
                index = idx,
                prompt = excerpt(p)
            )
        }

        // Run all prompts in parallel using supervisorScope.
        // Each child gets its OWN agentId (UUID) and emits its OWN spawn event,
        // so the UI renders one card per child with stable per-child dedupe.
        val results = supervisorScope {
            prompts.mapIndexed { idx, p ->
                async {
                    val brain = brainProvider(modelOverride)
                    // Scope brain's XML parser to subagent's tool set (same as executeSingle)
                    brain.toolNameSet = coreTools.keys
                    brain.paramNameSet = coreTools.values.flatMap { it.parameters.properties.keys }.toSet()
                    val runner = SubagentRunner(
                        brain = brain,
                        coreTools = coreTools,
                        deferredTools = deferredTools,
                        systemPrompt = config.systemPrompt,
                        project = project,
                        maxIterations = maxIter,
                        planMode = true, // read-only agents are always plan mode
                        contextBudget = contextBudget,
                        maxOutputTokens = maxOutputTokens,
                        apiDebugDir = subagentDebugDir("${description}-${idx + 1}"),
                        toolExecutionMode = toolExecutionMode,
                        approvalGate = approvalGate,
                        sessionApprovalStore = sessionApprovalStore,
                        hookManager = hookManager,
                        sessionMetrics = sessionMetrics,
                        fileLogger = fileLogger,
                        onDebugLog = onDebugLog,
                        onCheckpoint = onCheckpoint,
                        ideContext = ideContext,
                        agentConfig = config,
                    )

                    val childAgentId = generateAgentId()
                    val perChildDesc = promptPairs[idx].second
                    val childLabel = "${perChildDesc} #${idx + 1} (${config.name})"
                    runningAgents[childAgentId] = runner

                    // Emit ONE explicit spawn event for this child. The UI dedupes
                    // on agentId, so subsequent progress events for the same id
                    // update the existing card instead of spawning new ones.
                    onSubagentProgress?.invoke(
                        childAgentId,
                        SubagentProgressUpdate(
                            status = SubagentExecutionStatus.RUNNING,
                            label = childLabel,
                            model = brain.modelId,
                        )
                    )
                    entries[idx].status = SubagentExecutionStatus.RUNNING

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
                            val safe = if (progress.status == SubagentExecutionStatus.RUNNING) progress.copy(status = null) else progress
                            onSubagentProgress?.invoke(childAgentId, safe)
                        }

                        // Final per-child status
                        when (result.status) {
                            SubagentRunStatus.COMPLETED -> {
                                entries[idx].status = SubagentExecutionStatus.COMPLETED
                                entries[idx].result = result.result
                            }
                            SubagentRunStatus.FAILED -> {
                                entries[idx].status = SubagentExecutionStatus.FAILED
                                entries[idx].error = result.error
                            }
                        }
                        result
                    } catch (e: Exception) {
                        entries[idx].status = SubagentExecutionStatus.FAILED
                        entries[idx].error = e.message ?: "Unknown error"
                        // Tell the UI this specific child failed.
                        onSubagentProgress?.invoke(
                            childAgentId,
                            SubagentProgressUpdate(status = SubagentExecutionStatus.FAILED, error = e.message ?: "Unknown error")
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
        val DESCRIPTION_KEYS = listOf("description", "description_2", "description_3", "description_4", "description_5")

        /**
         * Fixed iteration cap matching the main agent — sub-agents run until
         * attempt_completion or context exhaustion, not until an arbitrary user-set number.
         */
        const val DEFAULT_MAX_ITERATIONS = 200

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

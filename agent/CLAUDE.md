# :agent Module

AI coding agent with ReAct loop, LLM-controlled delegation, interactive planning, and ~80 tools.

## LLM API

Uses Sourcegraph Enterprise's OpenAI-compatible API:
- Endpoint: `/.api/llm/chat/completions`
- Auth: `token` scheme via `Authorization: token <sourcegraph-access-token>`
- Constraints: 190K input tokens (configurable), no `system` role (converted to user with `<system_instructions>` tags), no `tool_choice`, strict user/assistant alternation
- Output limit varies per model — no hardcoded clamp. User configures maxOutputTokens in settings.
- Model: Auto-resolved from `GET /.api/llm/models` on first use via `ModelCache` (in `:core`). Priority: Anthropic Opus thinking > Opus > Sonnet. No hardcoded defaults.
- Message sanitization in `SourcegraphChatClient.sanitizeMessages()` (in `:core`): system→user, tool→user with plain text prefix "RESULT of {toolName}:" (not XML — prevents LLM echo hallucination), consecutive same-role merging
- **XML-in-content tool calls (2026-05-13 migration):** Tools are NOT declared via the OpenAI-compat `tools:[...]` request parameter. Tool definitions live exclusively in the system prompt via `ToolPromptBuilder` (in `:core`), which emits per-tool Usage examples (`<read_file><path>x</path></read_file>` shape). The LLM emits tool calls as XML in the assistant text content; `AssistantMessageParser` extracts them for dispatch. No native `tool_calls` SSE frames are requested or accepted — this path was removed to eliminate the mixed-signal dialect drift surface (the `tools:[...]` parameter activated Claude's native function-calling prior, fighting the system prompt's XML-in-text teaching). The U+200B zero-width-space placeholder for empty assistant tool-call messages was removed as part of this migration (Case 2 of the old `MessageSanitizer`); the tool→user coercion with `"TOOL RESULT:\n"` prefix remains.

## Architecture

```
AgentController (UI entry point, owns AgentCefPanel + JCEF bridges)
  → AgentService (orchestration, tool registration, session management)
    → AgentLoop.run() (ReAct loop, maxIterations=200)
      → BrainRouter (transparent for text-only; routes on image presence only)
        → OpenAiCompatBrain (text-only turns; no tools:[...] parameter sent)
        → SourcegraphCompletionsStreamClient (any image-bearing turn)
      → AssistantMessageParser (extracts XML tool calls from raw text content)
      → ContextManager (single-stage CC-style: dedup pre-pass → LLM summary at 88%)
      → LoopDetector (doom loop detection: 3 soft warning, 5 hard failure)
      → Tool execution with optional approval gate
      → Steering messages (ConcurrentLinkedQueue, drained at iteration boundary)
```

## Key Components

- **AgentLoop** (`loop/AgentLoop.kt`, ~1471 lines) — Core ReAct loop. Tool call processing, context compaction on overflow, parallel read-only tool execution (via coroutineScope+async), sequential write tool execution. Truncated tool call recovery — detects invalid JSON when finishReason=length, asks LLM to retry with smaller operation. Mid-loop cancellation support. Context overflow replay (compress + retry same request). Doom loop detection before each tool call. Streaming token estimate when usage is null. Plan mode execution guard (blocks `WRITE_TOOLS` even if LLM hallucinates them).
- **AgentService** (`AgentService.kt`, ~1549 lines) — Main orchestration service. Manages tool registration, session lifecycle, plan mode state (per-session via `PerSessionAgentState`, accessed by `isPlanModeActive()` / `setPlanModeActive(enabled)`), network-error recovery setup (brainFactory + cachedFallbackChain wiring), context management setup. Builds `AgentLoop` with all callbacks wired. Schema filtering for plan mode (removes write tools + `enable_plan_mode` from tool definitions before LLM call). Dynamic tool definition provider rebuilds system prompt when tool set changes.
- **AgentController** (`ui/AgentController.kt`) — UI entry point. Owns `AgentCefPanel`, routes user messages, handles steering, dispatches JCEF bridge callbacks (tool approval, plan approval, session history, artifact results). Wires `AgentService.executeTask()` with UI callbacks.
- **ContextManager** (`loop/ContextManager.kt`, ~795 lines) — Single-stage Claude-Code-style compactor (rewritten 2026-05-17 from the 1,427-line 3-stage Cline port):
  - Trigger: single 88% utilization gate (was 70/85/95% banding); per-model budget from `ModelCatalogService.getContextWindow()` via `effectiveMaxInputTokens()`
  - Pre-pass: `deduplicateFileReads()` — cheap input shaping; collapses repeated `read_file`/`create_file`/`edit_file` results for the same path to a 75-char placeholder, keeping the most recent read. Bounds the summarization prompt's own input.
  - Main: `summarizePrefix()` — LLM-summarize first ~70% of messages into a single assistant turn (TASK/FILES/DECISIONS/STATE/ERRORS/PENDING); preserves last ~30% verbatim. Image-bearing messages get `[+N image(s) attached]` placeholders in the prompt; tool-call-only turns fall back to the tool name.
  - Returns `CompactResult` sealed class (`Skipped` / `Cancelled` / `Failed` / `Compacted`). Callers (AgentLoop x3 sites, AgentService, AgentController, SubagentRunner) wire `slidingWindow(0.3)` as the hard-truncation fallback when `Failed` is returned.
  - Summary chaining: previous summary is fed into the next compaction's prompt, so multi-compaction sessions don't lose pre-compaction context.
  - Post-compaction: image parts stripped (bytes survive on disk), active skill content re-injected, active plan pointer re-injected.
  - PreCompact hook fires before summarization; cancellable.
  - Tracks active skill content, active plan path, TaskStore reference, previous summary.
- **LoopDetector** (`loop/LoopDetector.kt`, ~118 lines) — Detects identical consecutive tool calls. 3 identical = soft warning injected as system message. 5 identical = hard failure stops the loop.
- **BrainRouter** (`loop/BrainRouter.kt`, ~390 lines) — Implements `LlmBrain`; the agent loop's `brain.chatStream()` call site routes through it transparently. Routing rule (post-2026-05-05 simplification): text-only → `OpenAiCompatBrain` (`/.api/llm/chat/completions`); any image-bearing turn (image-only OR image+tools) → `SourcegraphCompletionsStreamClient` (`/.api/completions/stream`). **2026-05-13 migration:** The native-vs-XML merge path in BrainRouter was removed. Neither client sends `tools:[...]` any longer — tool definitions live in the system prompt only, and both clients forward no `tools` parameter. Tool call extraction is handled entirely by `AssistantMessageParser` on the raw SSE text stream; no `delta_tool_calls` frames are consumed. The routing logic is therefore simpler: only the image-vs-text distinction remains. Gateway-emitted `event: error` frames (HEIC/HEIF/BMP/TIFF/AVIF/SVG and unsupported document shapes per format_lab 2026-05-05) surface as a user-visible assistant message: `"Sourcegraph rejected this attachment: …. Supported image formats: PNG, JPEG, WebP."` instead of an empty bubble. **F-P6-4 — typed exception**: missing-attachment surfaces as `AttachmentMissingException` mapped to `VALIDATION_ERROR` with a clear "re-upload or remove the image" message instead of confusing `NETWORK_ERROR`. **F-P6-5 — tool-role coercion is intentional** (do NOT "fix"): the Cody stream schema only accepts the three speakers `human`/`assistant`/`system`, so stray tool-role turns coerce to `human` with the tool name preserved in the text body. Per-session isolation: `AttachmentStore` constructed per-session in `AgentService.wrapBrainWithRouter()`; recycled/fallback brains receive a fresh router pointed at the SAME session dir, never a stale store. The exact pattern locked in by Phase 5's `AttachmentUploadHandler` for the upload path. **Historical note (2026-05-05):** the two-step image+tools workaround (vision-summarize on /stream → tools call on /chat/completions) was deleted after format_lab confirmed Sourcegraph forwards `tools` on api-version=9. The prior `📷 image analyzed` UI badge, the `router-step1-` synthetic-response IDs, the abstention/empty-handling, and ~520 lines of step-1 framing-prompt anti-refusal hardening (commits 14361e88, 66f757f0, fa005e43, b4d0fb36, 4e3f607d) all went with it. Baselines `capabilities_lab_2026-04-22_*.json` and `capabilities_lab_2026-05-05_*.json` document the regime change.
- **MessageStateHandler** (`session/MessageStateHandler.kt`, ~302 lines) — Two-file JSON persistence (api_conversation_history.json + ui_messages.json). Atomic file writes via write-then-rename, per-session `kotlinx.coroutines.sync.Mutex`.
- **ResearchIndex** (`research/ResearchIndex.kt`) — auto-managed `{agentDir}/research/RESEARCH.md` index for the research sub-agent's dumps. Mirrors `MemoryIndex` shape: `load()` is auto-injected into the orchestrator's system prompt (max 200 lines); `onResearchFileCreated()` is a Kotlin-side hook fired by `CreateFileTool` when the persona writes a new dump file — race-safe via per-dir lock + atomic rewrite, idempotent on duplicate calls, self-edit guarded.
- **SessionLock** (`session/SessionLock.kt`) — `java.nio.channels.FileLock` on `.lock` file to prevent dual-instance access.
- **ToolRegistry** (`tools/ToolRegistry.kt`, ~229 lines) — Three-tier registry: core (always sent to LLM), deferred (available via `tool_search`), active-deferred (loaded during session). Reduces per-call schema tokens from ~10K to ~4K.
- **SystemPrompt** (`prompt/SystemPrompt.kt`, ~507 lines) — Builds the system prompt per turn. 11 sections following Cline's generic variant template: Agent Role → Task Management → Editing Files → Act vs Plan Mode → Capabilities → Skills → Deferred Tool Catalog → Rules → System Info → Objective → Memory → User Instructions.
- **InstructionLoader** (`prompt/InstructionLoader.kt`, ~453 lines) — Loads skill and agent config files from resources and disk. Handles YAML frontmatter parsing, substitution variable expansion (`$ARGUMENTS`, `$1`-`$N`, `${CLAUDE_SKILL_DIR}`). Dynamic injection via `` !`command` `` for preprocessing.
- **SpawnAgentTool** (`tools/builtin/SpawnAgentTool.kt`) — Primary tool for spawning subagents. Only `description` and `prompt` required. `agent_type` selects built-in or custom agents. Defaults to general-purpose. Explorer type restricted to read-only tools. Parallel fan-out via `prompt_2..5` + `description_2..5` (read-only agents only). `shared_prompt` (optional) factors out context common to all branches — `composePromptPairs` prepends it to every branch and rejects placeholder-stub branches that reference an unseen sibling prompt.
- **SubagentRunner** (`tools/subagent/SubagentRunner.kt`) — Executes subagent with isolated context and budget. Routes prompt construction through `buildComposedSystemPrompt()`, which delegates unconditionally to `SubagentSystemPromptBuilder`. Appends `COMPLETING_YOUR_TASK_SECTION` so all personas call `task_report`, not `attempt_completion`.
- **SubagentSystemPromptBuilder** (`tools/subagent/SubagentSystemPromptBuilder.kt`) — Stateless façade that calls the shared `SystemPrompt.build()` with sub-agent-scoped flags (`includeTaskManagement=false`, `includePlanModeSection=false`, `includeSubagentDelegationInRules=false`, `agentRoleOverride=<persona body>`), then appends the `COMPLETING_YOUR_TASK_SECTION` footer. Per-persona section overrides are read from `AgentConfig.promptSections`.
- **Network-error recovery** (in `loop/AgentLoop.kt`) — Two automatic recovery layers on NETWORK_ERROR/TIMEOUT after API retries are exhausted. **L1-recycle**: same-model brain recycle (throws away the `OpenAiCompatBrain` + its dead OkHttp socket/pool, rebuilds with the SAME model id; bounded by `MAX_SAME_TIER_RECYCLES`). **L2 tier escalation**: once recycles are exhausted, advance one tier down `cachedFallbackChain` (Opus thinking → Opus → Sonnet thinking → Sonnet, no Haiku) via `brainFactory`; one-way, no escalate-back. Gated by the `networkErrorStrategy` setting (`none` disables L2; `model_fallback` and `context_compaction` enable it). The eager cross-model `ModelFallbackManager` (L1-fallback) was **removed in Phase 6f** (audit agent-runtime:F-20) per the user's documented preference — recover at the SSE/connection layer (recycle), escalate tier only as a last resort. **Phase 7 followup F-P6FU-3 — vision-aware L2**: when the in-flight payload contains image parts, L2 skips non-vision-capable models in the chain via `ModelCatalogService.supportsVision()`, and surfaces a user-visible "no vision-capable fallback available, retry on primary or remove image" error if none remain. Pinned by `AgentLoopVisionFallbackTest`.
- **SharedCatalogHolder** (`SharedCatalogHolder.kt`) — Phase 7 followup F-P6FU-1 + F-P6FU-2 — extracted from `AgentService.getOrCreateSharedCatalog` so the keying contract has a unit test and the dead `tokenProvider` half of the original `Pair<String, () -> String?>` cache key is gone. Keyed solely on `sgUrl`. Warm-up coroutine survives a `getCatalog()` throw (no propagation up). Pinned by `SharedCatalogHolderTest`.
- **HookManager** (`hooks/HookManager.kt`) — 8 lifecycle hook types (TaskStart, UserPromptSubmit, TaskResume, PreCompact, TaskCancel, PreToolUse, PostToolUse, TaskComplete). Config: `.agent-hooks.json` in project root.
- **AttemptCompletionTool** (`attempt_completion`) — Explicit completion signal for the **orchestrator**. LLM must call this to end the session. Text-only responses (no tool calls) trigger escalating nudges (up to `MAX_NO_TOOL_NUDGES=4`) demanding `attempt_completion`. `allowedWorkers = {ORCHESTRATOR}` only.
- **TaskReportTool** (`task_report`) — Completion signal for **sub-agents** (replaces `attempt_completion` at the sub-agent boundary). Forces the sub-agent to produce a structured report (summary, findings, files, next_steps, issues) that flows directly into the parent LLM's tool result — unlike `attempt_completion`, which targets the user UI. `allowedWorkers = {CODER, REVIEWER, ANALYZER, TOOLER}`. Auto-injected by `SpawnAgentTool.resolveConfigToolsTiered()`; any `attempt_completion` in a config's `tools:` field is silently dropped and replaced by this tool.
- **StreamBatcher** (`ui/StreamBatcher.kt`) — 16ms EDT timer coalesces rapid SSE chunks into single JCEF bridge calls (~5000 → ~300 per response).

## Service & threading (Phase 4)

Follows the canonical conventions documented in `:core` ("Service & threading conventions"); module-specific notes:

- **Injected scope.** `AgentService`, `BackgroundPool`, `TicketDetectionPresenter` (and the other project services) take `cs: CoroutineScope` in their `@Service` constructor. `AgentController` is not a service and consolidates fire-and-forget launches onto its own `controllerScope` field (Phase 4 C2 collapsed 14 ad-hoc `CoroutineScope(Dispatchers.IO + SupervisorJob())` sites onto it).
- **Suspend builders.** `EnvironmentDetailsBuilder.build` and `MentionContextBuilder.buildContext` are `suspend fun`. `AgentLoop.environmentDetailsProvider: (suspend () -> String?)?`. PSI reads inside use `readAction { }` (writes-may-cancel) or `smartReadAction(project) { }` when index access is required (e.g., `MentionSearchProvider` for `PsiShortNamesCache`). EDT-affine reads (active editor, caret) wrap in `withContext(Dispatchers.EDT) { readActionBlocking { … } }`.
- **Survivor.** `IdeContextDetector` keeps a single `runReadAction { }` at the synchronous `@Service.init { }` chain (see TODO at `ide/IdeContextDetector.kt:114`, retire on 2026.1 platform bump).
- **Background `runBlocking`.** `ProjectContextTool` (and other BG-thread sites) use `runBlockingCancellable { … }` so cancellation propagates.

## System Prompt Structure (`SystemPrompt`)

Built by `SystemPrompt.build()`, called from `AgentService` at task start and when tool set changes. 11 sections following Cline's section ordering:

1. **Agent Role** — role, capabilities (IDE, debugger, integrations)
2. **Task Management** (optional) — Typed task system instructions + current task list rendered from `TaskStore` (when tasks exist)
3. **Editing Files** — edit_file vs create_file guidance, multi-change batching rules
4. **Act vs Plan Mode** — mode descriptions, blocked tools in plan mode, switching rules
5. **Capabilities** — core tools listing, deferred tool workflow hints, usage tips (database, sonar, project_context, render_artifact)
6. **Skills** (optional) — meta-skill auto-injection + available skills listing + active skill content (for compaction survival)
6b. **Deferred Tool Catalog** (optional) — categorized one-liner descriptions of deferred tools
6c. **Tool Definitions** — XML-format tool schemas (always on — tools are defined in the system prompt, not via API `tools` parameter)
7. **Rules** — IDE tool preference, read-before-edit, environment, communication, code changes, safety, task execution, subagent delegation
8. **System Info** — OS, IDE, shell, home dir, working directory
9. **Objective** — iterative task execution instructions, `<thinking>` tag usage
10. **Memory** — file-based MEMORY.md explanation + optional injected index content
11. **User Instructions** (optional) — project name, repo structure, custom instructions

## Tools (~30 core + ~50 deferred = ~80 total)

### Core Tools (always sent to LLM)

Registered in `AgentService.registerAllTools()`:

| Tool Name | Class | Purpose |
|-----------|-------|---------|
| `read_file` | ReadFileTool | Read file contents |
| `edit_file` | EditFileTool | SEARCH/REPLACE block edits |
| `create_file` | CreateFileTool | Create new files |
| `search_code` | SearchCodeTool | Regex search with output modes |
| `glob_files` | GlobFilesTool | Glob-pattern file discovery |
| `run_command` | RunCommandTool | Shell command execution (ShellResolver + DefaultCommandFilter + OutputCollector + ProcessEnvironment) |
| `revert_file` | RevertFileTool | Single-file revert |
| `background_process` | BackgroundProcessTool | Manage background processes (list/status/output/attach/send_stdin/kill) |
| `send_stdin` | SendStdinTool | Send input to a running process's stdin (accepts bgId) |
| `attempt_completion` | AttemptCompletionTool | Explicit task completion signal (orchestrator only) |
| `task_report` | TaskReportTool | Sub-agent completion: structured findings report for parent LLM (sub-agents only, auto-injected) |
| `think` | ThinkTool | No-op reasoning scratchpad |
| `ask_followup_question` | AskQuestionsTool | Ask user questions (simple or wizard mode) |
| `plan_mode_respond` | PlanModeRespondTool | Present plan in plan mode |
| `enable_plan_mode` | EnablePlanModeTool | Switch to plan mode |
| `use_skill` | UseSkillTool | Load and activate a skill |
| `new_task` | NewTaskTool | Propose a session handoff (user-confirmed): returns `HandoffProposed`, loop renders a preview card + suspends on `userInputChannel`; "Start fresh session" → `LoopResult.SessionHandoff`, "Keep chatting" → `LoopResult.Completed`. Orchestrator-only. |
| `render_artifact` | RenderArtifactTool | Interactive React component in chat |
| `find_definition` | FindDefinitionTool | PSI go-to-definition |
| `find_references` | FindReferencesTool | PSI find usages |
| `diagnostics` | SemanticDiagnosticsTool | IDE error/warning diagnostics |
| `tool_search` | ToolSearchTool | Search and activate deferred tools |
| `agent` | SpawnAgentTool | Spawn subagents (single or parallel fan-out) |

### Deferred Tools (loaded via `tool_search`)

| Category | Tools |
|----------|-------|
| Code Intelligence | find_implementations, file_structure, type_hierarchy, call_hierarchy, type_inference, dataflow_analysis, get_method_body, get_annotations, test_finder, structural_search, read_write_access, project_structure (resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets, refresh_external_project, add_source_root, set_module_dependency, remove_module_dependency, set_module_sdk, set_language_level, add_content_root, remove_content_root) |
| Code Quality | format_code, optimize_imports, refactor_rename, run_inspections, problem_view, list_quickfixes |
| VCS | changelist_shelve |
| Build & Run | build, spring, django, fastapi, flask, runtime_exec, java_runtime_exec (Java only), python_runtime_exec (Python only), runtime_config, coverage |
| Database | db_list_profiles, db_list_databases, db_query, db_schema, db_stats, db_explain |
| Utilities | project_context, current_time, ask_user_input |
| Debug | debug_step, debug_inspect, debug_breakpoints |
| Integration (conditional) | jira, bamboo_builds, bamboo_plans, sonar, bitbucket_pr, bitbucket_repo, bitbucket_review |

Integration tools are only registered when their service URL is configured in ConnectionSettings.

### Meta-Tools (single tool with `action` parameter)

These tools consolidate multiple operations behind an `action` enum:

| Meta-Tool | Action Count | Examples |
|-----------|-------------|----------|
| **runtime_exec** | 5 | get_running_processes, get_run_output, get_test_results, run_config, stop_run_config. Observation actions: `get_test_results` unified through `interpretTestRoot` — empty suites return `NO_TESTS_FOUND` (not `PASSED`) and failed runs propagate `isError=true`. Launch actions: `run_config` launches an existing run configuration with readiness detection (log-scrape + idle-stdout heuristic) and port discovery (lsof/ss/netstat) — idempotent: if an instance of the named configuration is already running it is stopped first (via `stopProcessesForConfig` helper) before launching; if the stop fails, `run_config` returns `STOP_FAILED` and does not launch a second instance; `stop_run_config` gracefully stops a running config. |
| **java_runtime_exec** | 3 | run_tests (JUnit/TestNG), compile_module (CompilerManager), rerun_failed_tests — registered only when Java plugin present. **run_tests `method` param accepts a single name ('testFoo') or a comma-separated list ('testFoo,testBar,testBaz')** to run multiple methods from the same class in one launch; output is aggregated into a single `ToolResult`. Native runner uses the JUnit 5 `PATTERNS` field (`"Class,m1|m2|m3"`) for 2+ methods; 0–1 methods keep today's `METHOD_NAME` / class-mode paths unchanged. Maven shell emits `-Dtest=Class#m1+m2+m3` (Surefire `+` separator); Gradle shell emits one `--tests 'Class.m'` flag per method. **TestNG with 2+ methods auto-routes through the shell fallback** (TestNG has no `PATTERNS` equivalent). Cap: `MAX_METHODS_PER_RUN=50`; bad identifiers (`#`, `.`, `;`, whitespace inside a name) return a specific invalid-name error; `METHOD_NAME_REGEX` rejects smuggled class paths. **run_tests runs a pre-flight `BuildSystemValidator` check** (Phase 2 Tasks 2.7–2.9) before any dispatch — catches main-sources classes, zero @Test methods, missing `settings.gradle` entries, and unregistered Maven modules with actionable suggestions. Authoritative Gradle/Maven paths from the validator supersede filesystem-derived subproject paths in the shell fallback; a breadcrumb (`Running tests in module: X (Build path: Y, N test methods detected in Z)`) is prepended to every successful result. **run_tests explicitly calls `ProjectTaskManager.build(module)` before launching JUnit** (commit 9b164bf3 guard: config is transient/unregistered so factory-default "Build" before-run task is NOT wired; we call the build API ourselves to prevent `initializationError`). On build failure, `run_tests` returns per-file compile errors (path:line:col — message) via `formatCompileErrors` instead of a bare count. Shell fallback parses Surefire/Gradle XML reports and returns `NO_TESTS_FOUND` on empty suites (not `PASSED`). **rerun_failed_tests** resolves the most-recent test session from `RunContentManager.allDescriptors` (or the session matching optional `session_id`), extracts FAILED/ERROR tests via `collectTestResults`, builds a filtered JUnit/TestNG run config via reflection (same as `run_tests`), and re-launches via full `RunInvocation` machinery. Error categories: `NO_PRIOR_TEST_SESSION` (no active test descriptor found — run `run_tests` first); `CONFIGURATION_NOT_FOUND` (original run config not found in `RunManager`). Returns informational (non-error) result when prior session had 0 failures. |
| **python_runtime_exec** | 2 | run_tests (pytest via PytestNativeLauncher → PyTestConfigurationType native runner first, shell-based PytestActions fallback), compile_module (python -m py_compile) — registered only when Python plugin present. Native runner integrates with PyCharm's test UI; fallback covers non-PyCharm IDEs. `method` param is a pytest `-k` keyword expression: single name ('test_foo'), boolean OR for multi-method ('test_foo or test_bar or test_baz'), or exclusion ('test_foo and not slow') — pytest runs the whole matched set in one process and returns an aggregated result. Pytest output reconciles verbose PASSED/FAILED/ERROR counts against the summary line and emits a heuristic warning when the suite reports passes with near-zero duration and no stdout. |
| **runtime_config** | 4 | get_run_configurations, create/modify/delete_run_config |
| **coverage** | 2 | run_with_coverage, get_file_coverage. `run_with_coverage` unified through `interpretTestRoot`; failed coverage runs propagate `isError=true`. **`method` param accepts comma-separated multi-method** ('testFoo,testBar') — uses JUnit 5 `PATTERNS` reflection so all methods run in one coverage session and produce a single merged snapshot. Same `MAX_METHODS_PER_RUN=50` cap and `METHOD_NAME_REGEX` validation as `java_runtime_exec`. TestNG multi-method is a hard error (no shell fallback; splitting would lose snapshot aggregation). **`on_existing_suite` param** (`replace` default / `append` / `ignore` / `ask`) suppresses IntelliJ's "replace-or-append coverage suite" modal that otherwise blocks the agent loop waiting for user input. Implemented by reflecting into `com.intellij.coverage.CoverageOptionsProvider.setOptionsToReplace(int)` with the caller's policy (REPLACE_SUITE=0 / ADD_SUITE=1 / IGNORE_SUITE=2 / ASK_ON_NEW_SUITE=3) before launch; prior value is snapshotted and restored in a `finally` block so the user's saved IDE preference is never silently clobbered (success / exception / timeout / coroutine-cancel paths all restore). Source-contract test `apply and restore calls are balanced and restore lives in finally` pins the invariant. |
| **debug_breakpoints** | 7 | add_breakpoint, method_breakpoint, exception_breakpoint, field_watchpoint, remove_breakpoint, list_breakpoints, attach_to_process |
| **debug_step** | 10 | get_state, step_over, step_into, step_out, force_step_into, force_step_over, resume, pause, run_to_cursor, stop |
| **debug_inspect** | 9 | evaluate, get_stack_frames, get_variables, set_value, thread_dump, memory_view, hotswap, force_return, drop_frame |
| **spring** | 15 | context, endpoints, bean_graph, config, version_info, profiles, etc. |
| **build** | 26 | maven_dependencies, maven_properties, maven_plugins, maven_profiles, maven_dependency_tree, maven_effective_pom, gradle_dependencies, gradle_tasks, gradle_properties, project_modules, module_dependency_graph, pip_list, pip_outdated, pip_show, pip_dependencies, poetry_list, poetry_outdated, poetry_show, poetry_lock_status, poetry_scripts, uv_list, uv_outdated, uv_lock_status, pytest_discover, pytest_run, pytest_fixtures |
| **project_structure** | 14 | resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets, refresh_external_project, add_source_root, set_module_dependency, remove_module_dependency, set_module_sdk, set_language_level, add_content_root, remove_content_root |
| **jira** | 17 | get_ticket, search_issues, transition, comment, log_work, etc. |
| **bamboo_builds** | 11 | build_status, trigger_build, get_build_log, get_test_results, etc. |
| **bamboo_plans** | 10 | get_projects, get_plans, get_project_plans, search_plans, get_plan_branches, get_build_variables, get_plan_variables, rerun_failed_jobs, trigger_stage, auto_detect_plan |
| **sonar** | 18 | issues, quality_gate, coverage, branch_quality_report, local_analysis, issue_facets, quality_gates_list, hotspot_detail, rule, current_user, security_hotspots, issues_paged, project_measures, source_lines, duplications, branches, analysis_tasks, search_projects |
| **bitbucket_pr** | 19 | create_pr, get_pr_detail, get_pr_commits, get_pr_activities, get_pr_changes, get_pr_diff, check_merge_status, approve_pr, merge_pr, decline_pr, update_pr_title, update_pr_description, get_my_prs, get_reviewing_prs, get_pr_participants, get_blocker_comment_count, get_linked_jira_issues, get_required_builds, get_prs_for_branch |
| **bitbucket_review** | 12 | add_pr_comment, add_inline_comment, reply_to_comment, add_reviewer, remove_reviewer, set_reviewer_status, list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment |
| **bitbucket_repo** | 8 | get_branches, create_branch, search_users, get_file_content, get_build_statuses, get_commit_build_stats (R-ADD-12), get_commit_pull_requests (R-ADD-5), list_repos. |

### runtime_exec Launch Actions — Implementation Notes

**Idempotent `run_config`:** Before launching, `run_config` calls the private `stopProcessesForConfig(project, settings.name)` helper (gracefulMs=10s, forceMs=5s). Outcomes: `NotRunning` → proceed silently; `StoppedGracefully(pids)` → append note to result; `StoppedForced(pids)` → append note to result; `FailedToStop(pids, msg)` → return `STOP_FAILED` immediately, do NOT launch. The resolved `settings.name` (post-name-resolution) is used, not the raw user-provided `config_name`.

**`stopProcessesForConfig` helper (private):** Shared stop state machine used by both `stop_run_config` (via its own handler-resolution logic) and `run_config` (idempotent pre-launch). Matches by exact `RunContentDescriptor.displayName == configName`. Graceful `destroyProcess()` → poll `STOP_POLL_INTERVAL_MS` → force `destroyProcess()` → poll `FORCE_KILL_TIMEOUT_MS`. Returns sealed `StopOutcome`: `NotRunning` / `StoppedGracefully(pids)` / `StoppedForced(pids)` / `FailedToStop(pids, message)`. Handles coroutine cancellation via `ensureActive()` and re-throws `CancellationException`.

**Readiness state machine:** `run_config` drives a three-state lifecycle — `process_started` (OS process alive, PID resolved) → `ready` (readiness signal received or idle-stdout threshold elapsed) → `exited` (process terminated before or after ready). The `wait_for_ready` parameter (default true) suspends the tool until the `ready` state or `ready_timeout_seconds` (default 120s).

**Readiness detection strategies (per config type):**

| Strategy | When active | Primary signal | Fallback |
|---|---|---|---|
| `http_probe` | `readiness_strategy=http_probe` OR `auto` with Spring Boot config detected | HTTP GET `{actuatorBasePath}/health` → 200 | Log-pattern (concurrent, first wins) |
| `log_pattern` | `readiness_strategy=log_pattern` OR explicit Spring Boot config (no http_probe) | Spring startup banners (Tomcat/Netty/Jetty/Undertow) | idle-stdout |
| `idle_stdout` | `readiness_strategy=idle_stdout` OR `auto` for non-Spring/non-http apps | No new stdout for 2000ms | timeout |
| `explicit_pattern` | `readiness_strategy=explicit_pattern` | User-supplied `ready_pattern` regex | timeout |
| `process_started` | `readiness_strategy=process_started` | OS process spawned (no wait) | N/A |

**HTTP probe details (`http_probe` strategy):**
- Probe URL: built from OS-discovered port (see Port Discovery below) + actuator paths from `SpringBootConfigParser.parseActuatorPaths()`. If OS discovery has not yet found a bound port, the HTTP probe is **skipped** and log-pattern readiness is used as fallback. Do NOT fall back to static config parsing for the port.
- Backoff: 200ms start, 1.5× multiplier, 2000ms cap. Grace period: 5s (JVM bootstrap window; connection-refused failures ignored).
- Spring Boot 3.x: tries `/actuator/health/readiness` path; falls back to `/actuator/health`.
- When `ready_url` param is provided, it is used **verbatim** — no OS discovery, no static parsing. The user owns correctness.
- Non-Spring + no `ready_url` → `READINESS_DETECTION_FAILED` (validation error before launch).

**Port discovery contract (`discoverListeningPorts`):**
- **Only authoritative source**: OS commands by PID — `lsof -iTCP -sTCP:LISTEN -P -n -p <pid>` (macOS), `ss -tlnp` with pid filter (Linux fallback), `netstat -ano | findstr LISTENING | findstr <pid>` (Windows).
- If OS command is unavailable or returns nothing, port is **omitted** from the result. No guessing, no static fallback.
- Log-banner regex (e.g., `Tomcat started on port(s): (\d+)`) is kept **only as a readiness signal** — the app has finished bootstrapping. The matched port number is intentionally discarded and not reported to the LLM.
- `SpringBootConfigParser` port fields (`serverPort`, `managementPort`) were **removed** (2026-04-21). Static parsing cannot see run-config overrides via VM options, env vars, active profiles, programmatic `setDefaultProperties`, cloud config, or random port (`server.port=0`). Only `actuatorBasePath` and `healthPath` are retained for probe URL path construction.

- Spring Boot (Tomcat/Netty/Jetty/Undertow): log-scrape using exact regex patterns on startup banners (e.g., `Tomcat started on port(s): (\d+)`, `Started [A-Za-z0-9._-]+ in [0-9.]+s`) — used as a **readiness signal only** (not a port source). Used as concurrent fallback when http_probe is active.
- Generic JVM applications: idle-stdout heuristic — no new stdout for 2000ms after process start and process is not terminated.
- JUnit/TestNG and Maven/Gradle test configs: blocked at input validation (`READINESS_DETECTION_FAILED` — readiness is not applicable for test runners).

**Error category taxonomy (all 17 categories):**
`CONFIGURATION_NOT_FOUND`, `AMBIGUOUS_MATCH`, `NO_RUNNER_REGISTERED`, `INVALID_CONFIGURATION`, `DUMB_MODE`, `BEFORE_RUN_FAILED`, `EXECUTION_EXCEPTION`, `PROCESS_START_FAILED`, `TIMEOUT_WAITING_FOR_READY`, `TIMEOUT_WAITING_FOR_PROCESS`, `EXITED_BEFORE_READY`, `READINESS_DETECTION_FAILED`, `PORT_DISCOVERY_FAILED`, `CANCELLED_BY_USER`, `UNEXPECTED_ERROR` (run_config); `PROCESS_NOT_RUNNING`, `STOP_FAILED` (stop_run_config and run_config idempotent pre-launch stop). The LLM should read the category prefix from the error message and take category-specific action rather than retrying blindly.
- `DUMB_MODE`: wait for indexing to complete; do not retry immediately.
- `EXITED_BEFORE_READY`: process crashed before readiness signal; inspect tail output for root cause before retrying.

**Detach-on-ready semantics:** When `wait_for_ready=true` and readiness is achieved, the tool clears `invocation.descriptorRef.set(null)` and `invocation.processHandlerRef.set(null)` before calling `Disposer.dispose(invocation)`. This detaches the running process from the `RunInvocation` lifecycle so it keeps running after the tool returns. Ownership transfers to `ExecutionManagerImpl` and the IDE's Run tool window. A subsequent "New Chat" (`SessionDisposableHolder.resetSession()`) does NOT cascade-stop already-detached processes — the app continues running across sessions until explicitly stopped via `stop_run_config` or the user terminates it manually.

**Debug-mode launches:** Use `runtime_exec(action=run_config, mode=debug, wait_for_pause=bool)` — this is the canonical path (subsumes the removed `debug_breakpoints.start_session` action). It drives the same `DefaultDebugExecutor` + `XDebugSession` semantics with the full readiness state machine, port discovery, and error taxonomy.

## Tool Selection

Two mechanisms:
1. **ToolRegistry three-tier** — Core tools always sent. Deferred tools loaded via `tool_search` during session. Active-deferred tools persist for the rest of the session.
2. **Schema filtering** — `AgentService` builds tool definitions dynamically per iteration. Plan mode removes write tools + `enable_plan_mode`. Act mode removes `plan_mode_respond`. `use_skill` removed when no skills are available.

Tool set stabilizes per session — tools only expand across messages, never shrink (deferred tools once activated stay active).

## IDE Context Detection

`IdeContextDetector` detects the runtime environment at agent startup:

- **IDE product/edition**: `ApplicationInfo.getInstance().build.productCode` (IU, IC, PY, PC, etc.)
- **Available plugins**: Java, Python (Pro/Core), Spring checked via `PluginManagerCore`
- **Detected frameworks**: Django (manage.py + deps), FastAPI, Flask scanned from requirements/pyproject
- **Detected build tools**: Maven (pom.xml), Gradle, pip, Poetry, uv scanned from project root

Result stored as `IdeContext` in `AgentService`. Tools that can't work in the current environment are never registered. `IdeContext.summary()` provides a human-readable string for the system prompt.

**Tool registration filter** (`ToolRegistrationFilter`): Guards tool categories:
- PSI + Code Intelligence → requires `hasJavaPlugin` OR `supportsPython` (either language enables all 14 PSI tools)
- Spring tools → requires `hasSpringPlugin && hasJavaPlugin`
- Build tools (Maven/Gradle) → requires `hasJavaPlugin`
- Debug tools → requires `hasJavaPlugin`
- `java_runtime_exec` (JUnit/TestNG runner + CompilerManager compile) → requires `shouldRegisterJavaBuildTools` (hasJavaPlugin)
- `python_runtime_exec` (pytest runner + `python -m py_compile`) → requires `shouldRegisterPythonBuildTools` (supportsPython)
- Database, runtime (observation), coverage, universal → always registered

Key files: `ide/IdeContext.kt`, `ide/IdeContextDetector.kt`, `ide/ProjectScanner.kt`

### IDE-Aware System Prompt (Plan D)

`SystemPrompt.build()` accepts an optional `IdeContext` parameter. Four sections adapt to the IDE environment:

| Section | Dynamic content |
|---------|----------------|
| `agentRole()` | IDE name (IntelliJ IDEA / PyCharm / WebStorm) |
| `capabilities()` | IDE context summary, specialized tool hints, language-specific curl tips |
| `rules()` | Tool preference examples (mvn/gradlew vs pytest), subagent list (spring-boot-engineer vs python-engineer) |
| `systemInfo()` | IDE name in system information |

**Backward compatibility:** `ideContext = null` produces the same prompt as before (IntelliJ-flavored defaults).

### Agent Persona Filtering

`AgentConfigLoader.filterByIdeContext()` gates language-specific personas:
- `spring-boot-engineer` → only when `IdeContext.supportsJava`
- `python-engineer` → only when `IdeContext.supportsPython`
- All other agents (code-reviewer, architect-reviewer, test-automator, etc.) → always available

`SpawnAgentTool` uses `getFilteredConfigs(ideContext)` so the LLM description only lists relevant agents.

### Deferred Tool Discovery

Four techniques help the LLM discover specialized tools over generic fallbacks:

1. **Task-to-tool hints table** — in `capabilities()`, maps common tasks (e.g., "Find API endpoints") to tool_search keywords (e.g., "spring") with "Instead of" column showing the inferior approach
2. **IdeContext category hints** — when IdeContext is non-null, lists specialized tool categories (spring, django, build, debug, database) in the primacy zone
3. **Related tool suggestions** — `ToolSearchTool.getRelatedToolsHint()` appends complementary tool suggestions when returning search results (e.g., loading "spring" suggests "build, coverage, db_schema")
4. **Framework tool promotion** — `ToolRegistrationFilter.shouldPromoteFrameworkTool()` promotes detected framework tools from deferred to core

### Python Framework Tools

Three framework meta-tools follow the SpringTool pattern (thin dispatcher + action files):

| Tool | Actions | Detect | Promote |
|------|---------|--------|---------|
| `django` | 14 | manage.py + django in deps | Yes |
| `fastapi` | 10 | fastapi in deps | Yes |
| `flask` | 10 | flask in deps | Yes |

Implementation: File-scan primary (regex on .py files) via `PythonFileScanner` (shared utility with directory exclusions and sensitive value redaction), PSI-optional. Zero compile-time Python plugin dependency.

**PythonFileScanner** (`tools/framework/PythonFileScanner.kt`): Shared utility for all Python framework tools.
- `shouldScanDir()` — excludes 17+ directories (venv, .venv, node_modules, __pycache__, .git, .tox, .mypy_cache, .pytest_cache, dist, build, .eggs, site-packages, hidden dirs)
- `scanPythonFiles(baseDir, filter)` — walks with `onEnter { shouldScanDir(it) }` to skip excluded subtrees entirely
- `redactIfSensitive(key, value)` — redacts values for keys containing SECRET_KEY, PASSWORD, API_KEY, TOKEN, DATABASE_URL, PRIVATE_KEY, AWS_SECRET, etc.

**Router/blueprint prefix resolution:** FastAPI routes compose `APIRouter(prefix=...)` + `app.include_router(..., prefix=...)` into full URL paths. Flask routes compose `Blueprint(url_prefix=...)` + `app.register_blueprint(..., url_prefix=...)` similarly.

## System Prompt Snapshot Tests

Golden snapshot tests verify the exact system prompt output for 7 IDE variants:
- `prompt-snapshots/null-context.txt` — backward compatibility baseline
- `prompt-snapshots/intellij-ultimate.txt` — Java/Kotlin + Spring + Gradle
- `prompt-snapshots/intellij-community.txt` — Java/Kotlin + Maven, no Spring
- `prompt-snapshots/pycharm-professional.txt` — Python + Django + Poetry
- `prompt-snapshots/pycharm-community.txt` — Python + FastAPI + uv
- `prompt-snapshots/webstorm.txt` — base tools only (no language-specific content)
- `prompt-snapshots/intellij-ultimate-mixed.txt` — Java + Python + Spring + Django

**When you change SystemPrompt.kt:**
1. Run `./gradlew :agent:test --tests "*SNAPSHOT*"` — expect failures on changed variants
2. Review the diff to confirm changes are intentional
3. Run `./gradlew :agent:test --tests "*generate all golden snapshots*"` to regenerate
4. Run `./gradlew :agent:test --tests "*SNAPSHOT*"` again — all should pass
5. Commit the updated snapshot files alongside the code change

## Language Intelligence Providers

PSI tools delegate language-specific logic to pluggable providers via `LanguageProviderRegistry`.

**Interface:** `LanguageIntelligenceProvider` (16 operations: symbol resolution, file structure, type hierarchy, implementations, type inference, dataflow, callers/callees, metadata, body, access classification, test discovery, diagnostics, structural search)

**Implementations:**
- `JavaKotlinProvider` — wraps existing PsiToolUtils + inline Java/Kotlin PSI logic
- `PythonProvider` — reflection-based Python PSI access via `PythonPsiHelper`
  - All 16 operations implemented except: `analyzeDataflow` (returns null — no Python equivalent) and `structuralSearch` (returns null — not supported for Python)
  - Requires `PythonCore` plugin (Community-level, no Professional APIs needed)
  - Zero compile-time dependency on Python plugin — all PSI access via `Class.forName` / `Method.invoke`

**Registry:** `LanguageProviderRegistry` resolves provider by `PsiFile.language.id`. Thread-safe (ConcurrentHashMap). Initialized in `AgentService.registerAllTools()`.

**Tool pattern:** Each PSI tool accepts the registry in its constructor, resolves the provider by iterating `allProviders()` until one finds the symbol (no hardcoded Java fallback). If no provider exists for the language, returns "Code intelligence not available for {language}."

**Cycle detection:** `findCallers()` in both providers uses an `IdentityHashMap`-based visited set to prevent infinite recursion on mutual call chains (A→B→A). PsiElement identity (not `.equals()`) is used because `PsiElement.equals()` is not a stable contract.

Key files: `ide/LanguageIntelligenceProvider.kt`, `ide/LanguageProviderRegistry.kt`, `ide/JavaKotlinProvider.kt`

## ToolRegistry Internals

- **Thread safety**: Registry map uses `ConcurrentHashMap`. `activateDeferred()` and `resetActiveDeferred()` are `@Synchronized` to prevent races when integration tools are toggled from multiple coroutines.
- **Deferred activation**: Integration tools (Jira, Bamboo, Sonar, Bitbucket, DB) are registered lazily. `reregisterConditionalTools()` re-evaluates integration availability at runtime (e.g., after settings change) and adds/removes tools without restarting the session.
- **Unregistration**: `unregisterDeferred(toolName)` removes a single tool from the deferred or active-deferred pool and invalidates the name cache; used to clean up integration tools that lose their service connection.
- **One-liner extraction**: `extractOneLiner(toolResult)` DRY helper used by all tools — strips markdown, trims whitespace, and returns the first meaningful sentence for notification display.
- **ToolResult.error()**: Factory companion method for consistent error ToolResult construction; avoids scattered inline error strings.

## Context Management

All context management runs through `ContextManager` — a single-stage Claude-Code-style compactor. Rewritten 2026-05-17 (commits `4eea82cfd` / `edf979fb0` / `b995bef6a`) from the previous 1,427-line 3-stage Cline port.

### Flow

`compact(brain, hookManager, force, iterationsSinceLastUser) → CompactResult`:

Two-tier memory model: pre-user handoff (L1) + anchor user (L2) + post-user working memory summary (L3, optional) + verbatim recent tail (L4).

1. **Threshold gate** — single 88% utilization. Returns `Skipped` if below (unless `force=true`).
2. **`PRE_COMPACT` hook** — cancellable.
3. **Dedup pre-pass** — `deduplicateFileReads()` collapses repeated reads (unchanged).
4. **Find last user message** — `findLastUserIndex()`. If -1, fall through to degenerate single-summary path.
5. **Case detection** — Case B if `totalUserMessageCount == lastCompactionUserMessageCount` (no new user since last compaction), else Case A.
6. **Build L1** — Case A: LLM-summarize messages before the anchor user, folding in `previousPreUserSummary`. Case B: reuse `previousPreUserSummary` verbatim, no LLM call. Skipped entirely if the pre-user prefix is empty.
7. **L2** — the most recent user message, verbatim.
8. **Decide L3** — build if `iterationsSinceLastUser > 5` OR estimated post-L1 utilization ≥ 88%. The 5-gate is a cost optimization; over-budget pressure always forces L3.
9. **Build L3 + L4** — token-weighted cut: walk backward summing `estimateMessageTokens` until 20% of budget is reached; snap to tool-role boundary so L4 never starts with `assistant` (avoids the `MessageSanitizer.kt:70` consecutive-role merge). Layer 3 LLM-summarizes from `lastUserIdx+1` up to the cut, folding in `previousPostUserSummary`. Layer 4 is verbatim from the cut to the end.
10. **Reassemble** — `[L1?][L2][L3?][L4...]`.
11. **Post-cleanup** — strip image parts, re-inject active skill, re-inject active plan (unchanged).
12. **Save state** — `previousPreUserSummary`, `previousPostUserSummary`, `lastCompactionUserMessageCount`.
13. **Persist** — `lastPromptTokens = null`, invoke `onHistoryOverwrite`.

Result: `CompactResult.Compacted` on any successful path (including partial successes). `CompactResult.Failed` only when summarization was attempted and no attempt succeeded (call site falls back to `slidingWindow(0.3)`).

### Spec

`docs/superpowers/specs/2026-05-18-context-compaction-two-tier-design.md`

### Failure handling

`compact()` returns `CompactResult` (sealed): `Skipped(utilizationPercent)`, `Cancelled(reason)`, `Failed(reason)`, `Compacted(tokensBefore, tokensAfter, summaryChars)`. Every external caller (3 sites in `AgentLoop`, `AgentService.compactContext()`, `AgentController` Compact button, and the sub-agent equivalents) wires `slidingWindow(0.3)` — preserved from the original code, repurposed — as the hard-truncation fallback when `Failed` is returned. At `AgentLoop` length-overflow recovery, three consecutive `Failed` results abort the loop with a user-visible error.

### Key details
- **Compaction threshold**: 88% of `effectiveMaxInputTokens()` (single value; was 70/85/95% banding). Per-model from `ModelCatalogService.getContextWindow(currentModelRef)`; falls back to constructor `maxInputTokens` (default 90K) if catalog miss.
- **Token tracking**: `lastPromptTokens` from API response, invalidated after compaction
- **Tool output**: Independent of compaction. `truncateOutput()` middle-truncates per-tool result at 50KB/100KB (60% head + 40% tail). `ToolOutputSpiller` separately spills `>30KB` to disk and returns a preview + file path.
- **Active skill / active plan**: Stored in `ContextManager`, re-injected as assistant messages after compaction so the model knows the skill/plan is still in force.
- **Task store**: `ContextManager.attachTaskStore(TaskStore)` wires the task store; current tasks rendered into system prompt Section 2.
- **History overwrite callback**: `onHistoryOverwrite(messages, deletedRange: Pair<Int,Int>)` persists the modified conversation via `MessageStateHandler`. The `Pair<Int,Int>` is the index range of removed messages (`0 to splitIdx`), not token counts.
- **Summary chaining**: `previousSummary` field; each compaction folds the prior summary into the next prompt to retain pre-compaction context across multi-day sessions.

## TaskStore + Task Tools

> See `docs/plans/2026-04-18-task-system-port.md` for the port plan and `docs/research/2026-04-18-claude-code-task-system-research.md` for the design research.

Four hook-exempt tools let the LLM manage typed tasks within a session:

| Tool | Purpose |
|---|---|
| `task_create` | Create a task with title (`subject`), description, and `activeForm` (active-spinner text). Dependency edges (blocks/blockedBy) are added separately via task_update. |
| `task_update` | Update status (`pending`/`in_progress`/`completed`/`deleted`), title, or description |
| `task_list` | Minimal summary list (id, title, status) — low token cost |
| `task_get` | Full task detail including dependency edges |

**Persistence:** `~/.workflow-orchestrator/{proj}/agent/sessions/{sessionId}/tasks.json` (atomic JSON, Mutex-guarded, same write cadence as conversation history).

**Hook-exemption:** `task_create`, `task_update`, `task_list`, `task_get` bypass `PreToolUse` and `PostToolUse` hooks entirely — they are internal bookkeeping and should never trigger user approval gates.

**Dependency DAG:** `blocks`/`blockedBy` fields form a directed graph. `TaskStore` enforces no-cycle invariant via DFS on every mutation.

**UI push:** Each mutation emits `WorkflowEvent.TaskChanged` via `EventBus`, which `AgentController` converts to a `task-update` bridge event for the webview.

**System prompt integration:** `ContextManager.attachTaskStore(store)` wires the store. `renderTaskProgressMarkdown()` serializes current tasks into Section 2 ("Task Management") on every prompt rebuild, so task state survives compaction.

## Real-Time Steering

Users can send messages while the agent is working. Messages are injected at iteration boundaries (between tool calls), not mid-tool.

**Flow:**
1. User types in chat input during agent execution (input stays enabled in "steering mode")
2. `AgentController` routes message to `AgentLoop`'s steering queue (`ConcurrentLinkedQueue<SteeringMessage>`)
3. At the top of each ReAct loop iteration, `AgentLoop` drains the queue
4. Drained messages added to `ContextManager` as user messages
5. LLM sees the steering context on the next call and adjusts its approach

## Run/Test Tool Disposal — RunInvocation Pattern

`java_runtime_exec.run_tests` and `coverage.run_with_coverage` route all per-launch IntelliJ listener/descriptor/connection tracking through `RunInvocation` (`agent/tools/runtime/RunInvocation.kt`) — a per-launch `Disposable` parented to the current session. Every run starts with:

```kotlin
val invocation = project.service<AgentService>().newRunInvocation("run-tests-$classname")
try {
    invocation.attachListener(eventsListener, resultsViewer)   // EventsListener with defense-in-depth proxy
    invocation.attachProcessListener(handler, processListener) // 2-arg auto-cleanup form
    invocation.subscribeTopic(messageBusConnection)            // auto-disconnect on dispose
    invocation.onDispose {
        RunContentManager.getInstance(project).removeRunContent(executor, descriptor)
    }
    // ... suspend until result ...
} finally {
    Disposer.dispose(invocation)  // disposes on success / timeout / exception / cancel
}
```

**Session parent:** `AgentService.newRunInvocation(name)` delegates to `SessionDisposableHolder` which owns a per-session `Disposable`. On "new chat" (`AgentController.newChat()` → `AgentService.resetForNewChat()` → `sessionDisposableHolder.resetSession()`), all outstanding invocations cascade-dispose — process handlers killed, listeners unwired, descriptors removed from `RunContentManager` — before the next session starts.

**Why this matters:** Pre-Phase-3, each `run_tests` call leaked a `RunContentDescriptor`, a `TestResultsViewer.EventsListener`, a streaming `ProcessListener`, and occasionally a 5-minute build-watchdog Thread. After enough agent runs, the user's own `Run | Run 'MyTest'` failed with `initializationError`. The `RunInvocation` dispose-on-all-paths contract eliminates the leak surface.

**Testing invariant:** `RunInvocationLeakTest` (9 source-text + MockK assertions) locks in the contract — every `addEventsListener` must coexist with `Disposer.register` or `RunInvocation`; every `addProcessListener` 1-arg must pair with `removeProcessListener`, else use the 2-arg `Disposable` form; every outer function must contain a `finally { Disposer.dispose(invocation) }` block; the tool source must contain a literal `removeRunContent` call. Run: `./gradlew :agent:test --tests "*RunInvocationLeak*"`.

**Phase 5 will clone this as `DebugInvocation`** for `XDebugSession` listener leaks (add-only API, no matching remove). Same shape: `attachListener`, `subscribeTopic`, `onDispose`, idempotent `dispose`, session-scoped parent. If you extend the pattern, keep the `onDispose` literal-in-tool-file constraint so the Task-2.7-style source-text tests remain meaningful.

## Tool Execution

- **Read-only tools**: Execute in parallel via `coroutineScope { async { ... } }` (read_file, search_code, diagnostics, etc.)
- **Write tools**: Execute sequentially (edit_file, run_command, etc.)
- **Write tools set** (`WRITE_TOOLS` in `AgentLoop`): edit_file, create_file, run_command, revert_file, background_process, send_stdin, format_code, optimize_imports, refactor_rename
- **Approval tools** (`APPROVAL_TOOLS`): edit_file, create_file, run_command, revert_file — require user approval unless already allowed for session
- **Per-tool timeouts**: `withTimeoutOrNull` wraps every execution — default 120s, `run_command` 600s, `agent` (SpawnAgentTool) unlimited. Timeout returns an error ToolResult; the LLM can retry or adjust.
- **RunCommandTool component architecture**:
  - **ShellResolver** (`tools/command/ShellResolver.kt`) — Platform-aware shell detection. Windows: Git Bash → PowerShell → cmd. Unix: `/bin/bash` → `$SHELL` → `/bin/sh`. macOS/Linux uses `/bin/bash` (not `sh` which is `zsh` on macOS). Login shell `-l` flag for PATH recovery.
  - **DefaultCommandFilter** (`security/DefaultCommandFilter.kt`) — Hard-block pre-spawn patterns: fork bombs, `rm -rf /`, `sudo`, `mkfs`, etc. Returns rejection before process is created.
  - **OutputCollector** (`tools/command/OutputCollector.kt`) — 50/50 line-based head/tail truncation, disk spill for large outputs, Unicode sanitization.
  - **ProcessEnvironment** (`tools/command/ProcessEnvironment.kt`) — Strips 35+ sensitive vars (credentials, tokens, keys), blocks 25 vars from the LLM-provided `env` parameter, applies 15 anti-interactive overrides (TERM, PAGER, GIT_TERMINAL_PROMPT, etc.).
- **RunCommandTool parameters**: `command` (required), `working_dir` (optional — the canonical key; `cwd` accepted only as a legacy fallback in `CommandApprovalPayload`), `env` (optional JSON object for LLM-provided environment variables, filtered through security blocklist), `separate_stderr` (optional boolean for separate stderr capture).
- **Two distinct safety layers for commands**: (1) `DefaultCommandFilter` — hard-block patterns, pre-spawn, in `security/` package; (2) `CommandSafetyAnalyzer` — risk classification for the approval gate, called in `AgentLoop` not in `RunCommandTool`.
- **CancellationException**: Always re-thrown (never swallowed) so coroutine scope cancellation propagates correctly through the loop.
- **Registration failures**: Tracked and logged; no more silent swallowing of deferred activation errors.
- **Token estimate**: Computed after output truncation, not before, so context fill is accurate.
- **Doom loop detection**: 3 identical consecutive tool calls = warning. 5 = hard failure.
- **Context overflow**: Compress via `ContextManager` + REPLAY the failed request (OpenCode pattern)
- **Task system**: Managed via `TaskStore` (four tools: `task_create`, `task_update`, `task_list`, `task_get`). Tasks are hook-exempt (bypass PreToolUse/PostToolUse). Progress rendered into system prompt Section 2 from `ContextManager.attachTaskStore`.
- **Dumb mode checks**: `OptimizeImportsTool` and `FormatCodeTool` check `DumbService.isDumb(project)` before operating — prevents removing used imports during indexing
- **Middle-truncation**: Runtime/build/coverage tools use first-60% + last-40% truncation (via shared `truncateOutput()`) instead of head-biased `.take(N)` — preserves error messages and stack traces at end of output. Exception: `run_command` uses **tail-biased** truncation (`OutputCollector.processOutputTailBiased`) — keeps the last N lines and drops the head — because build/test output has exit summaries and failure traces at the tail.
- **No-op detection**: `FormatCodeTool` and `OptimizeImportsTool` compare before/after text and report "no changes needed" when nothing changed
- **Debug session unification**: All debug tools (step, inspect, breakpoints) resolve sessions via `IdeStateProbe.debugState()` — both agent-started and user-started sessions are visible
- **All breakpoint types**: `list_breakpoints` shows line, exception, field watchpoint, and method breakpoints (not just `XLineBreakpoint`)
- **Evaluate timeout**: `debug_inspect` evaluate action wraps with `withTimeoutOrNull(10s)` to prevent indefinite hangs
- **Inspection profile**: `RunInspectionsTool` and `ListQuickFixesTool` use `profile.isToolEnabled()` instead of `isEnabledByDefault` — respects user's active inspection profile
- **ToolResult.isError semantics**: `SemanticDiagnosticsTool` returns `isError=false` when problems are found (successful result), `isError=true` only for actual tool failures

## Tool Approval

- **ApprovalPolicy**: Per-tool approval rules. Three policies: `ALWAYS_APPROVE` (trust, no gate), `ALLOW_FOR_SESSION` (user can grant once-per-session), `ALWAYS_PER_INVOCATION` (must approve every time). `run_command` is hardcoded to `ALWAYS_PER_INVOCATION` — it can never be allowed for session.
- **SessionApprovalStore**: Holds the set of tools the user has approved for the current session. Lives at `AgentController` level (not `AgentLoop`), so approvals persist across loop restarts within the same session. Cleared on new chat.
- **Pre-execution guard errors**: Logged to `fileLogger` + `sessionMetrics` so they appear in traces and are counted in the scorecard.

## Tool Output Management

- **ToolOutputConfig**: Per-tool output size limits. `DEFAULT` cap = 50K chars (most tools); `COMMAND` cap = 100K chars (`run_command` + high-volume tools like `run_tests`, `run_with_coverage`, `run_inspections` full-project). Tools override via `outputConfig` property on `AgentTool` interface (e.g., `RunCommandTool` uses `ToolOutputConfig.COMMAND`).
- **`grep_pattern` parameter**: Optional regex available on tools in `OUTPUT_FILTERABLE_TOOLS` set. The LLM passes a pattern; only matching lines are returned (no context lines — simple line filter). Applied before spill/truncation. Implemented via `ToolOutputConfig.applyGrep()`.
- **`output_file` parameter**: Boolean (not a path). When `true`, full output is saved to disk via `ToolOutputSpiller` and the context receives a preview (first 20 + last 10 lines) with the file path. The LLM can then use `read_file` or `search_code` on the saved file.
- **ToolOutputSpiller**: Auto-spills large outputs (>30K chars) to `{sessionDir}/tool-output/{toolName}-{epochSec}-output.txt`. Preview = head 20 lines + tail 10 lines + file reference. Falls back to truncation if disk write fails.
- **Per-tool spill wiring (Phase 7, 2026-04-18):** Every high-output tool calls `AgentTool.spillOrFormat(content, project)` directly so full content lands on disk and `ToolResult.spillPath` points to it. Wired: runtime (`runtime_exec`, `java_runtime_exec`, `python_runtime_exec`, `coverage`), debug (`debug_inspect` get_variables/thread_dump/memory_view; evaluate KEEPs its small cap), inspection (`list_quickfixes`, `problem_view`, `run_inspections`, `diagnostics` — structured preview: prose head-20 inline, full JSON on disk), DB (`db_query`, `db_explain`, `db_schema`, `db_stats`), PSI (`find_references`, `call_hierarchy`, `type_hierarchy`). `AgentLoop` also keeps a post-execution safety net that spills any >30K content a tool didn't self-spill. Acceptance suite: `SpillingWiringTest` pins the contract (12 tests).
- **Tool-produced images:** tools may return `imageRefs` on `ToolResult`; `AgentLoop` writes them as `ContentBlock.ImageRef` blocks alongside the `ContentBlock.ToolResult` in a single user `ApiMessage`. Same downstream path as user-pasted images — `BrainRouter` detects via `messages.any { it.hasImageParts() }` (position-independent, fires whether the image arrives on a `role="user"` or `role="tool"` turn), hydrates bytes from `AttachmentStore`, and routes through `/.api/completions/stream`. Image-bearing tool results are represented in compaction by `[+N image(s) attached]` placeholders in the summarization prompt (so the summarizer can describe what was relevant) and the image bytes are stripped post-summary while surviving on disk for replay. Toggle: `PluginSettings.enableToolImageAutoload` (Settings → Tools → Workflow Orchestrator → AI Agent → Multimodal). Wired tools: `jira.download_attachment` (image MIME types only — png, jpeg, webp, gif). Pinned by `BrainRouterToolImageRoutingTest` and the `image+tools via tool-result origin still routes through stream` case in `BrainRouterTest`.
- **System prompt guidance**: RULES section instructs the LLM on when to use `grep_pattern` (targeted extraction), `output_file` (save for later), and to prefer dedicated tools over raw commands.

## Plan Mode Enforcement

Two-layer enforcement:

1. **Schema filtering** (`AgentService` tool definition provider, ~line 834) — Removes write tools and `enable_plan_mode` from tool definitions before each LLM call. The LLM never sees blocked tools. In act mode, removes `plan_mode_respond`.
2. **Execution guard** (`AgentLoop.run()`, ~line 955) — Checks `planMode && toolName in WRITE_TOOLS` as a safety net for cached tool calls from before mode switch.

**Blocked in plan mode:** edit_file, create_file, run_command, revert_file, background_process, send_stdin, format_code, optimize_imports, refactor_rename, enable_plan_mode
**Always available:** read_file, search_code, glob_files, diagnostics, find_definition, find_references, think, agent, tool_search, ask_followup_question, plan_mode_respond, etc.

**Transition:** `AgentService.setPlanModeActive(false)` → tools restored on next LLM call → dashboard UI updated.

**Per-session state:** Plan-mode is per-session. Each `Session` holds the persisted boolean (`Session.planModeEnabled`); the in-memory mutable counterpart is `PerSessionAgentState.planModeActive`, keyed by session ID in `AgentService.perSessionStates`. Read via `AgentService.isPlanModeActive()`, write via `setPlanModeActive(enabled)`. The application-scoped `AtomicBoolean` previously held on `AgentService` was removed in the cross-IDE-delegation Plan 0 refactor.

## Error Handling

- **API retry**: 5 attempts, exponential backoff with **equal jitter** (base 1s, max 30s; `computeBackoffMs` returns `[computed/2, computed]` so no retry is ever near-instant — switched from full jitter `[0, computed]` in 2026-05), retries on 429, 5xx, NETWORK_ERROR, TIMEOUT
- **Retry pacing invariant**: EVERY recovery path that re-calls the LLM is paced via `computeBackoffMs` and bounded. The four that previously did a bare delay-less `continue` (output-length truncation, upstream gateway timeout, context-overflow replay, empty-`choice` guard) now back off; truncation (`MAX_TRUNCATED_RETRIES=8`) and gateway-timeout (`MAX_UPSTREAM_TIMEOUT_RETRIES=5`) carry their own caps + reset on a clean response. Pinned by `AgentLoopRetryPacingTest` + `AgentLoopBackoffHelperTest`.
- **Context overflow**: ContextManager compaction triggered + replay
- **Streaming**: Heuristic token estimate when API returns usage: null
- **Truncated tool calls**: When finishReason=length produces invalid JSON, asks LLM to retry with smaller operation
- **Upstream gateway timeout**: When Sourcegraph's Cody Gateway closes the SSE stream mid-response with a `context deadline exceeded` error frame (or when partial XML signals the same), `SourcegraphChatClient` sets `finishReason=upstream_timeout` and `AgentLoop` synthesizes a tool-result error nudging the model to retry with smaller chunks. Continue.dev pattern (`continue/gui/src/redux/thunks/streamNormalInput.ts:257-291`). Detector: `core/ai/GatewayErrorDetector.kt`. Plan: `docs/superpowers/plans/2026-05-05-sse-gateway-timeout-handling.md`.
- **Network-error recovery**: After API retries exhaust, L1-recycle (same-model brain recycle on a fresh OkHttp pool, bounded by `MAX_SAME_TIER_RECYCLES`) then L2 tier escalation (one-way advance down `cachedFallbackChain`: Opus thinking → Opus → Sonnet thinking → Sonnet, no Haiku). Gated by `AgentSettings.networkErrorStrategy` (`none` = recycle only; `model_fallback`/`context_compaction` = recycle + L2). The eager `ModelFallbackManager` cross-model fallback was removed in Phase 6f (agent-runtime:F-20).
- **Offline fail-fast** (`FailureReason.OFFLINE`): BEFORE the retry budget is consumed at the `TIMEOUT_ERRORS` seam, the loop calls `networkProbe.checkNow(llmProbeUrl)` (bounded ~3s probe of the Sourcegraph host via the `:core` `NetworkStateService`). If the probe confirms the tunnel is down (common right after unlocking while the VPN reconnects), the turn fails immediately with `FailureReason.OFFLINE` rather than burning ~2 minutes of retries into a dead socket. `AgentController` renders the existing Retry pill with an offline-specific caption; clicking it re-runs the last task via `retryLastTask()`. `checkNow()` also flips global state to OFFLINE, which pauses the feature pollers and arms the reconnection probe. ONLINE result → transient blip → existing retry machinery runs unchanged. `networkProbe`/`llmProbeUrl` are optional `AgentLoop` constructor params (null in tests/sub-agents). Pinned by `AgentLoopOfflineFailFastTest`. Spec: `docs/superpowers/specs/2026-05-26-network-connectivity-resilience-design.md`.
- **Dialect drift detection** (`agent/session/DialectDriftDetector.kt`): Detects when the LLM emits tool-call XML in an unrecognised format that `AssistantMessageParser` would fail to extract, and surfaces a descriptive user-visible warning rather than silently losing the action. Covered patterns (as of 2026-05-13 migration): standard `<tool_name>...</tool_name>` XML (the expected format); legacy native-function-call leakage formats `<tool>`, `<tool_use>`, `<function>`, `<function_use>`, `<function_call name=...>` (all five are now detected and flagged so that session recovery can nudge the model back to the correct XML shape). **Canonical-tool-boundary-aware (post-dda9f6882 Gap 1 fix):** `hasDialectMarker` and `redactDialectMarkers` strip/protect the values of code-carrying param tags (`content`, `new_string`, `old_string`, `diff`, `code` — same set as `AssistantMessageParser.CODE_CARRYING_PARAMS`) when those tags appear inside a recognised canonical tool call block. This prevents the detector from corrupting file content, diffs, or code snippets that legitimately reference dialect-shaped strings (e.g. docs discussing function-calling formats). A bare `<content>` floating outside a canonical tool wrapper is NOT protected. Implementation: `stripCanonicalCodeParams()` for detection, `collectCanonicalCodeParamRanges()` for the protected-ranges list passed to `replaceOutsideRanges()`.

## Token Management

- Token display shows current context window fill via `ContextManager`, not cumulative API total
- `lastPromptTokens` updated from API response after each call — authoritative source
- Invalidated after any compaction to force fresh estimate
- `toolDefinitionTokens` tracks schema overhead, updated by `AgentLoop`

## Interactive UI

- **Plan card** — `plan_mode_respond` renders JCEF plan card with step status icons, per-step comments, approve/revise buttons. Uses `suspendCancellableCoroutine` for non-blocking approval.
- **Plan editor tab** — Full-screen `FileEditor` with `JBCefBrowser`, clickable file links, comment textareas.
- **Question wizard** — `ask_followup_question` (wizard mode) renders inline wizard with single/multi-select options, back/skip/next navigation, "Chat about this" textarea, summary page.
- **Interactive artifacts** — `render_artifact` renders LLM-generated React components in a sandboxed iframe (`react-runner` via `agent/webview/src/sandbox-main.ts`). See `RenderArtifactTool.SCOPE_HINT` and `sandbox-main.ts` `fullScope` for the canonical list.
  - **React hooks**: useState, useEffect, useCallback, useMemo, useRef, useReducer, useLayoutEffect, useId, useTransition, Fragment
  - **UI primitives (shadcn-compatible, Radix-backed)**: Card family, Badge, Button, Alert family, Skeleton, Separator, ScrollArea, Tabs, Accordion, Breadcrumb, Dialog, Sheet, Popover, HoverCard, DropdownMenu, Tooltip, Select, Switch, Checkbox, Slider, Toggle, Avatar, Input, Label, Textarea, Progress
  - **Charts**: Recharts (all chart types + polar primitives)
  - **Icons**: All Lucide icons by name
  - **Animation**: motion/react (motion, AnimatePresence, useMotionValue, useTransform, useSpring, useInView, useScroll, useAnimation)
  - **Viz**: d3 (full namespace), cobe createGlobe, roughjs, react-simple-maps
  - **Node-edge graphs**: @xyflow/react (React Flow) — ReactFlowCanvas, Background, Controls, MiniMap, Handle, hooks
  - **Headless tables**: @tanstack/react-table — useReactTable, row models, flexRender, createColumnHelper
  - **Date/time**: date-fns — format, formatDistance, parseISO, addDays, etc.
  - **Color**: colord for color manipulation
  - **Bridge**: bridge.navigateToFile(path, line), bridge.isDark, bridge.colors, bridge.projectName

  Sandbox uses Tailwind Play CDN with CSS variables from `artifact-sandbox.html`. No network access — all data must be inline.

## Interactive Artifact Pipeline (Self-Repair Loop)

`render_artifact` is **not** fire-and-forget — the tool suspends until the sandbox iframe reports the actual render outcome, so the LLM sees missing-symbol / runtime errors as tool results and can self-correct.

**Correlation.** Each `render_artifact` call generates a UUID `renderId` that threads through: `RenderArtifactTool` → `ArtifactResultRegistry` → `AgentCefPanel.renderArtifact()` → `chatStore.addArtifact` → `<ArtifactRenderer>` → iframe `postMessage` → iframe echoes result → `ArtifactRenderer.reportToKotlin` → JCEF bridge → `AgentController.parseAndDispatchArtifactResult` → `ArtifactResultRegistry.reportResult()` → suspended `CompletableDeferred` completes → tool returns structured `ToolResult`.

**Key components:**
- **`ArtifactResultRegistry`** (`@Service(Service.Level.PROJECT)` in `tools/builtin/`) — owns `ConcurrentHashMap<String, CompletableDeferred<ArtifactRenderResult>>`. `renderAndAwait(payload, timeout=30s)`.
- **`ArtifactRenderResult`** (sealed class in `tools/AgentTool.kt`) — `Success(heightPx)`, `RenderError(phase, message, missingSymbols, line)`, `Timeout(timeoutMillis)`, `Skipped(reason)`.
- **Missing-symbol extraction** (`extractMissingSymbols` in `sandbox-main.ts`) — regex-parses V8 ReferenceError/TypeError phrasings to produce `missingSymbols: string[]`.
- **Exactly-once reporting**: `ArtifactRenderer` holds `reportedRef`, reset on source/renderId change. Stale iframe messages rejected by `isForCurrentRender` guard.

## Revert Architecture

`revert_file` tool provides single-file revert. Git allowlist checks have been removed from `RunCommandTool` — destructive git command policy is deferred to a future policy layer. The system prompt guides the LLM toward `revert_file` for file reverts.

## Conversation Persistence & Durable Execution

Faithful port of Cline's two-file session persistence (message-state.ts + disk.ts):

**Per-session files:**
```
~/.workflow-orchestrator/{proj}/agent/
├── sessions.json                          # List<HistoryItem> global index
└── sessions/
    └── {sessionId}/
        ├── api_conversation_history.json  # List<ApiMessage> — what goes to the LLM
        ├── ui_messages.json               # List<UiMessage> — what the chat UI shows
        ├── .lock                          # Per-session FileLock (prevents dual-instance)
        └── plan.json                      # Plan state (if active)
```

**Save cadence:** Per-change under `kotlinx.coroutines.sync.Mutex` (ports Cline's p-mutex). Every `addToClineMessages`, `updateClineMessage`, `addToApiConversationHistory` atomically rewrites the file via write-then-rename. No timer, no batching.

**Key classes:**
- `MessageStateHandler` — owns both in-memory arrays + mutex + save logic
- **Empty-assistant guard**: `addToApiConversationHistory` drops writes for assistant messages whose content is empty/blank AND whose tool-call list is empty. These turns are provider-error artefacts that, persisted, train the model to mimic the empty pattern on retry. Cleanup for any pollution predating this guard is exposed via `pruneTrailingEmptyAssistants()` and called from the retry/resume flows in `AgentService`.
- **Assistant turn persistence (2026-05-13 migration):** New assistant turns are persisted as a **single** `ContentBlock.Text(rawText)` containing the LLM's complete text output — XML tool calls are inline in that text, not split into separate `ContentBlock.ToolUse` blocks. `ContentBlock.ToolUse` is **retained as a deserialization type** for legacy session compatibility: when `ApiMessage.toChatMessage()` encounters a `ContentBlock.ToolUse` block from a pre-migration session, `ToolUseXmlRenderer` renders it inline as XML so `AssistantMessageParser` can re-parse it normally on resume. New writes never produce `ContentBlock.ToolUse`.
- `SessionLock` — `java.nio.channels.FileLock` on `.lock` file

**Streaming persistence:** Every LLM chunk persists with `partial: true`. On stream end, flipped to `partial: false`. On abort: synthetic assistant turn with `[Response interrupted by user]` marker.

**Resume flow:**
1. Load `ui_messages.json` then trim trailing resume/cost-less messages
2. Load `api_conversation_history.json` then pop trailing user message if interrupted
3. Acquire session lock
4. Push full `ui_messages` to webview via `_loadSessionState` bridge
5. Build `[TASK RESUMPTION]` preamble with time-ago and optional user text
6. Rebuild ContextManager with `ApiMessage.toChatMessage()` conversion
7. Continue execution via `initiateTaskLoop(newUserContent)`

## Checkpoint System v2

Per-user-message file-copy snapshots backed by `SessionCheckpointStore`. Implemented in `agent/src/main/kotlin/.../checkpoint/`.

### Storage layout
```
~/.workflow-orchestrator/{proj}/agent/sessions/{sid}/checkpoints/
  msg-{ts}/
    files/<absolute-path-tree>/  ← pre-edit bytes captured on first-touch
    meta.json                    ← { messageTs, userText, createdAt, createdPaths, touchedPaths }
```

**Cross-platform snapshot paths.** Absolute paths are normalized via `snapshotRelative(path)`: backslashes → forward slashes, `C:` → `C` (drive letter sans colon), leading `/` stripped. So `/Users/me/Foo.kt` → `Users/me/Foo.kt` and `C:\Users\me\Foo.kt` → `C/Users/me/Foo.kt`. This prevents Java's `File(parent, child)` from discarding the parent when given a Windows-absolute child path, which would have written snapshots OVER the original source files.

### Capture trigger
`AgentLoop` calls `checkpointStore.captureIfFirstTouch(currentUserMsgTs, path)` for every path argument of every `WRITE_TOOLS` invocation, just before the tool executes. First touch per (msg, path) copies bytes; subsequent calls no-op. Non-existent paths are recorded in `meta.json.createdPaths` instead.

### Aggregate diff
`aggregateDiff()` walks all `msg-N` dirs, finds the earliest snapshot per file (the session baseline), and diffs baseline→current via LCS line-count. Total adds/removes summed across files. Updated in UI after every write tool via the `updateAggregateDiff` JCEF bridge.

### Revert flavors
- `revertToMessage(targetTs)` — undo target message and everything after. Restores files to their earliest snapshot in the to-be-reverted range; deletes files created in that range; drops invalidated `msg-N` dirs; returns the userText for input-restoration.
- `revertFileToBaseline(path)` — single-file revert. No history truncation.

### UX
- **Bottom bar** (EditStatsBar): `+N −M  K files  ▾  ⟲ Revert all`. Expanded shows per-file rows with individual `⟲` buttons.
- **Hover on user message** (UserMessageRevertButton): shows `⟲ Time-travel here`. Click → restores files, truncates chat history, **pushes the user-typed text back into the chat input** (true time-travel UX — user can edit and re-send).

### Limitations
- `run_command`, `background_process`, `send_stdin` writes are NOT snapshotted (no path arg). Documented.
- `refactor_rename` snapshots both `from_path` and `to_path` independently; double-revert may produce duplicates.
- External (non-agent) writes during an active turn are not tracked.
- **Steering-and-revert invariant**: steering messages are in-memory only (`ContextManager.addUserMessage`); they DO NOT call `messageStateHandler.addToApiConversationHistory`. Reverting to a user message correctly drops only the persisted api entries because `UiMessage.conversationHistoryIndex` reflects the apiHistory.size-1 at insertion time. Pinned by `MessageStateHandlerTruncateTest.truncateMessagesAtTs preserves correctness when STEERING_RECEIVED uiMessages interleave`. If a future change starts persisting steering to apiHistory, this test fails — re-derive the index math then.

### Key files
- `checkpoint/CheckpointModels.kt` — data classes (`CheckpointMeta`, `FileChange`, `FileStatus`, `AggregateDiff`, `RevertResult`)
- `checkpoint/SessionCheckpointStore.kt` — disk I/O + capture/restore/aggregate
- `checkpoint/DiffCalculator.kt` — LCS line counter (pure function)
- `AgentLoop.kt` — pre-write capture hook
- `AgentService.kt` — `revertToUserMessage` / `revertFileToBaseline` / `getAggregateDiff` / `firstUserMessageTs` / `loadUiMessages`
- `AgentController.kt` — bridge wiring + live push to UI after write tools
- `webview/.../EditStatsBar.tsx` — bottom-bar UI
- `webview/.../UserMessageRevertButton.tsx` — hover affordance

## Storage Tiers

The plugin writes to four distinct roots. Anything the agent's read tools must reach has to live in one of the first two; anything else is invisible to `read_file` / `read_document` / `search_code` and the agent will reject the path with "outside project".

| Tier | Root | What lives here | Reachable by agent reads? |
|---|---|---|---|
| 1. Project | `{project.basePath}` | User code; project-scoped agent assets (`.workflow/skills/`, `.workflow/agents/`, `.agent-hooks.json`). Agent **writes** allowed here. | Yes |
| 2. Per-session agent data | `~/.workflow-orchestrator/{slug}-{sha6}/agent/sessions/{id}/` | `api_conversation_history.json`, `ui_messages.json`, `tasks.json`, `plan.json`, `checkpoints/msg-{ts}/files/<path-tree>/ + meta.json` (per-user-message copy-on-write snapshots; v2 system — see Checkpoint System v2 section above), `attachments/` (image uploads), `tool-output/` (spilled tool output), **`downloads/`** (artifact downloads from feature modules). Agent **writes** allowed into `{agentDir}/memory/` AND `{agentDir}/research/` — see `PathValidator.resolveAndValidateForWrite`. | Yes — via `PathValidator.resolveAndValidateForRead` |
| 3. Cross-session agent data | `~/.workflow-orchestrator/{slug}-{sha6}/agent/` (parent of `sessions/`) and `~/.workflow-orchestrator/{slug}-{sha6}/logs/` | `sessions.json` global index, `pr-review-sessions.json`, `pr-review-findings/`, `agent-YYYY-MM-DD.jsonl` logs (7-day rotation). | Yes — same allowlisted root |
| 4. User-global agent data | `~/.workflow-orchestrator/` | `agents/` (user personas), `skills/` (user skills), `pricing.json`, `trace-fallback/`, `diagnostics/`. | Yes — same allowlisted root |
| OUT | `java.io.tmpdir`, `PathManager.systemPath/`, IntelliJ config | OkHttp HTTP cache, `PersistentStateComponent` XML, anything under system temp. | **No.** Anything written here is unreachable to agent read tools. |

**Convention:** Per-session data goes under `sessions/{id}/`. Cross-session data goes directly under `agent/`. Anything outside the `~/.workflow-orchestrator/` tree is invisible to the agent.

**Cross-module storage routing.** Feature modules (`:jira`, `:bamboo`, etc.) that download artifacts on the agent's behalf must land bytes in tier 2, not in `java.io.tmpdir`. The channel is the `:core` coroutine context element [`SessionDownloadDir`](../core/src/main/kotlin/com/workflow/orchestrator/core/services/SessionDownloadDir.kt) — installed by [`AgentLoopAttachmentScope`](src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoopAttachmentScope.kt) alongside `SessionAttachmentAccess`, read by feature modules via `SessionDownloadDir.current()` without taking a `:agent` dependency. When `current()` returns null (UI handlers, tests, sub-agents that skip the wrap) callers fall back to system temp — the original behavior — keeping non-agent paths working.

Wired today: `:jira` `download_attachment` lands at `{sessionDir}/downloads/jira-{attachmentId}/{filename}`. Future feature-module download tools (Bamboo artifacts, Bitbucket file blobs, etc.) should follow the same pattern with their own `{source}-{id}/` subfolder.

## File-Based Memory System

Per-project, file-backed auto-memory patterned after Claude Code. No specialized tools — the LLM operates on memory files with `read_file`, `create_file`, `edit_file`.

- **Location:** `~/.workflow-orchestrator/{proj}/agent/memory/`
- **Index:** `MEMORY.md` — one line per memory (`- [Title](file.md) — hook`). Truncated at 200 lines by `MemoryIndex.load()` before injection.
- **Individual memories:** `<type>_<topic>.md` with YAML frontmatter (`name`, `description`, `type ∈ {user, feedback, project, reference}`). Loaded on demand via `read_file` when their index entry looks relevant.
- **Injection:** `AgentService.executeTask()` calls `MemoryIndex.load(memoryDir)` at session start and passes the result through `SystemPrompt.build(memoryIndex = …, memoryIndexPath = …)`. Injected once per session — mid-session edits to `MEMORY.md` are visible only after the LLM re-reads the file.
- **Sub-agents:** `memory: none` (default) → no injection. `memory: project` → same `memoryIndex` as the orchestrator.
- **PathValidator:** `resolveAndValidateForWrite()` accepts `{agentDir}/memory/` as an additional allowed root for `edit_file` / `create_file`.

No keyword search, no tag search, no cross-session conversation recall, no automatic extraction. The LLM decides relevance by scanning the always-injected `MEMORY.md` index and fetching individual memories as needed.

**Research dir parallel.** The research sub-agent (see "Bundled specialist agents" → `research`) writes per-session dumps to `{agentDir}/research/` via the same `PathValidator` allow-list mechanism — `resolveAndValidateForWrite` accepts both `{agentDir}/memory/` and `{agentDir}/research/`. Dump filenames follow `YYYY-MM-DD-{topic-slug}-{sessionIdSuffix}.md` (suffix appended by `ResearchIndex.onResearchFileCreated`). The research dir also holds an auto-managed `RESEARCH.md` index (auto-injected into the orchestrator's system prompt like `MEMORY.md`) — see `ResearchIndex.kt`.

## Interactive Debugging

Agent has full programmatic access to IntelliJ's debugger via `AgentDebugController`:
- **Breakpoints**: Set/remove/list line breakpoints with conditions, log expressions, temporary flags
- **Session control**: Launch debug sessions, step over/into/out, resume, pause, run-to-cursor, stop
- **Inspection**: Get debug state, stack frames, variables (recursive with depth control), evaluate expressions
- **Run configs**: Create/modify/delete IntelliJ run configurations (`[Agent]` prefix for safety)
- **Async pattern**: `MutableSharedFlow(replay=1)` wraps XDebugger's callback-based API into coroutines
- **Skills**: `systematic-debugging` (with escalation) + `interactive-debugging` (LLM-only, activated on escalation)

## User-Extensible Skills

- Format: SKILL.md with YAML frontmatter (Agent Skills standard)
- Project: `{projectBasePath}/.workflow/skills/{name}/SKILL.md`
- User: `~/.workflow-orchestrator/skills/{name}/SKILL.md`
- Project overrides user if same name
- Discovery: descriptions loaded at session start, full content on activation
- Invocation: `/skill-name args` in chat, toolbar dropdown, or LLM calls `use_skill(skill_name="name")`
- Active skill injected into system prompt (survives compaction via rebuild)
- Built-in skills: `systematic-debugging`, `interactive-debugging`, `create-skill`, `git-workflow`, `brainstorm`, `writing-plans`, `tdd`, `subagent-driven`, `executing-plans`

**Frontmatter fields:**
| Field | Default | Description |
|-------|---------|-------------|
| `name` | directory name | Skill identifier, becomes /slash-command |
| `description` | -- | When to use. LLM uses this for auto-invocation |
| `disable-model-invocation` | false | true = only user can invoke, hidden from LLM |
| `user-invocable` | true | false = only LLM can invoke, hidden from / menu |
| `allowed-tools` | null | Hard tool whitelist when skill active |
| `preferred-tools` | [] | Soft tool preference (additive) |
| `context` | -- | "fork" = run in isolated subagent |
| `agent` | -- | Subagent type when context: fork |
| `argument-hint` | -- | Autocomplete hint for arguments |

**Substitutions:** `$ARGUMENTS`, `$1`-`$N`, `${CLAUDE_SKILL_DIR}`
**Dynamic injection:** `` !`command` `` runs shell at preprocessing time (10s per cmd, 30s total, 10K cap)
**Description budget:** 2% of context window (max 16K chars).

### Skill Language Variants (Plan D)

`InstructionLoader.getSkillContent()` accepts an optional `IdeContext` parameter. When non-null, it loads a language-specific variant file alongside the base `SKILL.md`:

- IntelliJ (INTELLIJ_ULTIMATE, INTELLIJ_COMMUNITY) → `SKILL.java.md`
- PyCharm (PYCHARM_PROFESSIONAL, PYCHARM_COMMUNITY) → `SKILL.python.md`
- Other IDEs → base only

Variant content is **appended** after the base (base + "\n\n" + variant). If no variant file exists, only the base is returned.

**5 skills with variants:**
| Skill | Java variant content | Python variant content |
|-------|---------------------|----------------------|
| `tdd` | JUnit 5, MockK, Gradle, Spring Boot test annotations | pytest, fixtures, coverage, markers |
| `interactive-debugging` | CGLIB proxies, JDWP, Spring exceptions | Django template debug, debugpy, common Python exceptions |
| `systematic-debugging` | Spring diagnostics, bean context, Testcontainers | Django/FastAPI diagnostics, import errors, venv issues |
| `subagent-driven` | Java verification commands, spring-boot-engineer | Python verification commands, python-engineer |
| `writing-plans` | Gradle/Maven build commands | pytest, pip/Poetry/uv, Django management |

Variant files live alongside `SKILL.md` in the same skill directory (bundled classpath or user/project filesystem).

## Agent Tool (Subagent Management)

The `agent` tool spawns subagents:

**Single sub-agent:** `agent(description="...", prompt="...", agent_type="coder")`
**Parallel fan-out (read-only agents only):** `agent(description="overall", prompt="task 1", prompt_2="task 2", prompt_3="task 3", description_2="...", description_3="...")`
**Shared context across branches:** `agent(description="...", shared_prompt="<the 4000-word payload>", prompt="angle 1", prompt_2="angle 2", ...)` — `shared_prompt` is **prepended to every branch** (`composePromptPairs` in `SpawnAgentTool`), so the model states a large common payload once instead of duplicating it into each `prompt_N`. Pure, unit-testable composition (`SpawnAgentToolPromptCompositionTest`). When fanning out, a `prompt_N` that looks like a placeholder referencing a sibling prompt it can't see (e.g. `[Same prompt as above …]`) is **rejected** with a hint pointing at `shared_prompt`, rather than dispatched literally. Absent `shared_prompt` → byte-for-byte unchanged from before.
**Model override:** `agent(prompt="...", model="claude-sonnet-...")`

**No LLM-callable resume/kill/send.** The UI Kill button in the chat panel cancels a running agent via `AgentController.cancelAgent(agentId)` — there is no equivalent LLM tool path. There is no `run_in_background`, `resume`, `kill`, or `send` parameter on the `agent` tool; those were aspirational and were never built.

**Built-in types:** general-purpose, explorer (PSI-powered, read-only, thoroughness: quick/medium/very thorough), coder, reviewer, tooler
**Bundled specialist agents** (from `agent/src/main/resources/agents/`): code-reviewer, architect-reviewer, test-automator, spring-boot-engineer, refactoring-specialist, devops-engineer, security-auditor, performance-engineer, research — overridable by user/project agents. The `research` persona is web-only (`web_fetch` + `web_search`) and produces a single markdown dump per session under `{agentDir}/research/`; the parent agent receives a file path, not the findings inline.
**Custom types:** Any agent defined in `.workflow/agents/{name}.md` or `~/.workflow-orchestrator/agents/{name}.md`

## Subagent Coordination

### File Ownership
`FileOwnershipRegistry` (in `SubagentModels.kt`) prevents concurrent workers from editing the same file. Write tools acquire ownership before proceeding; `read_file` warns if owned by another worker. Released on worker completion/failure/kill. Orchestrator exempt, whole-file granularity.

### Parent↔Child Messaging
`WorkerMessageBus` (in `SubagentModels.kt`) enables bidirectional communication via Kotlin `Channel(capacity=20, DROP_OLDEST)`. Messages consumed at ReAct loop iteration boundaries.

### WorkerContext
Coroutine context element carrying `agentId`, `workerType`, `messageBus`, and `fileOwnership` to all tools within a worker's scope.

### No Wall-Clock Timeouts
Workers are bounded by iteration limits (default 200, same as orchestrator) and context budget, not wall-clock timeouts.

## Custom Subagents

User-definable agent definitions via markdown files with YAML frontmatter:
- Project: `{basePath}/.workflow/agents/{name}.md`
- User: `~/.workflow-orchestrator/agents/{name}.md`

**Frontmatter fields:**
| Field | Default | Description |
|-------|---------|-------------|
| `name` | filename | Unique identifier |
| `description` | -- | When to delegate (required) |
| `tools` | inherit all | Tool allowlist |
| `disallowed-tools` | [] | Tool denylist |
| `model` | inherit | Model override |
| `max-turns` | — | Not parsed — all sub-agents use `DEFAULT_MAX_ITERATIONS = 200` |
| `skills` | [] | Skills preloaded at startup |
| `memory` | none | Persistent memory: `none` (no memory injected) or `project` (inherit orchestrator's project memory). Aliases `inherit`/`user` are accepted and treated as `project` — there is only one memory dir today. |
| `prompt-sections` | _(all on)_ | Per-section opt-in/opt-out — see table below |

**`prompt-sections` sub-fields** (kebab-case in YAML, all optional):

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `capabilities` | bool | true | Section 5 — core tools listing + hints |
| `rules` | bool | true | Section 7 — IDE preference, safety, communication |
| `editing-files` | `auto`/`true`/`false` | `auto` | Section 3; `auto` = include iff `edit_file`/`create_file` in tool set |
| `memory` | `none`/`project` | `none` | Section 10 + memory XML blocks; `none` suppresses both. `inherit`/`user` are aliases for `project` (kept for backward-compat). |
| `objective` | bool | true | Section 9 — iterative task execution instructions |
| `system-info` | bool | true | Section 8 — OS, IDE, shell, home dir |
| `user-instructions` | bool | true | Section 11 — project name, repo structure, custom instructions |

Bundled opt-ins: `security-auditor` and `architect-reviewer` declare `memory: project`. All others default to `memory: none`.

## Unified Sub-agent Prompt Pipeline

Sub-agents build their system prompt via `SubagentSystemPromptBuilder` (in `tools/subagent/`), which delegates to the same `SystemPrompt.build()` the orchestrator uses — ensuring IDE-aware sections (role, capabilities, rules, system info) are consistent between parent and child agents.

**Composition flow:**
1. `SpawnAgentTool` resolves `AgentConfig` + passes parent `IdeContext` to `SubagentRunner`
2. `SubagentRunner.buildComposedSystemPrompt()` delegates unconditionally to `buildUnifiedSystemPrompt()`
3. `buildUnifiedSystemPrompt()` calls `SubagentSystemPromptBuilder.build()` with scoped flags:
   - `includeTaskManagement = false` — sub-agents don't own task trees
   - `includePlanModeSection = false` — sub-agents are act-only
   - `includeSubagentDelegationInRules = false` — sub-agents can't spawn further
   - `agentRoleOverride = <persona systemPrompt body>` — replaces section 1
   - Per-section flags from `AgentConfig.promptSections` (YAML `prompt-sections:`)
4. Appends `COMPLETING_YOUR_TASK_SECTION` footer (task_report instructions)

**Dialect-drift correction is threaded through this path (do not drop it).** `buildUnifiedSystemPrompt()` is wired as `AgentLoop.systemPromptProvider`, so it is rebuilt before every LLM call. On each rebuild it consumes `messageStateHandler?.consumeDialectDriftFlag()` and passes `dialectDriftDetected` into `SubagentSystemPromptBuilder.build()` → `SystemPrompt.build()`, exactly mirroring the orchestrator's `AgentService.systemPromptBuilder`. This is what injects the one-shot corrective `<system-reminder>` after a sub-agent emits an incompatible tool-call dialect (`<function_calls>`/`<invoke>`/`<tool_call>`). The drift is *detected* in the shared `MessageStateHandler` (which raises the flag), but the *correction* lives in this prompt-assembly path — sub-agents have their own assembly path, so any drift/recovery behavior wired into `AgentService.systemPromptBuilder` must be mirrored here or sub-agents silently miss it (the bug that let sub-agents run away emitting dialect XML; `dialectDriftDetected` defaults to `false` so the snapshots below stay byte-stable).

**IdeContext propagation:** `SpawnAgentTool` passes the parent's `IdeContext` into `SubagentRunner`. A sub-agent running in PyCharm sees "PyCharm" in role and system-info sections; one running in IntelliJ IDEA sees "IntelliJ IDEA". Context is null-safe — omitting it produces IntelliJ-flavored defaults (backward compatible).

**Snapshot tests:** `SubagentSystemPromptSnapshotTest` pins 5 variants to lock in composed output:

| Snapshot file | Persona | IdeContext |
|---|---|---|
| `code-reviewer-intellij-ultimate.txt` | code-reviewer | IU + Spring + Gradle |
| `spring-boot-engineer-intellij-ultimate.txt` | spring-boot-engineer | IU + Spring + Gradle |
| `python-engineer-pycharm-professional.txt` | python-engineer | PyCharm Pro + Django + Poetry |
| `test-automator-null-context.txt` | test-automator | null (baseline) |
| `architect-reviewer-intellij-community.txt` | architect-reviewer | IC + Maven, no Spring |

Regenerate: `./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*generate all golden*"`
Validate: `./gradlew :agent:test --tests "*SubagentSystemPromptSnapshotTest*"`

Snapshots live in `agent/src/test/resources/subagent-prompt-snapshots/`.

## Observability

### SessionMetrics
Per-session metrics accumulator (`observability/SessionMetrics.kt`). Records tool durations, API latencies, counts.

### AgentFileLogger
Structured JSONL agent logs (`observability/AgentFileLogger.kt`). Location: `~/.workflow-orchestrator/{proj}/logs/agent-YYYY-MM-DD.jsonl`. 7-day retention.

## Security

- **PathValidator** (`tools/builtin/PathValidator.kt`) — canonical path comparison prevents traversal (`../../etc/passwd`)
- **DefaultCommandFilter** (`security/DefaultCommandFilter.kt`) — hard-block pre-spawn filter for dangerous patterns (fork bombs, `rm -rf /`, `sudo`, `mkfs`, etc.). Runs inside `RunCommandTool` before process creation.
- **CommandSafetyAnalyzer** (`security/CommandSafetyAnalyzer.kt`) — risk classification for shell commands. Called in `AgentLoop` for the approval gate, NOT inside `RunCommandTool`.
- **ProcessEnvironment** (`tools/command/ProcessEnvironment.kt`) — strips 35+ sensitive environment variables and blocks 25 vars from LLM-provided `env` parameter

## Rich Chat UI

**Full JCEF Architecture:**
- Entire agent tab is a single `JBCefBrowser` — toolbar, chat, and input all rendered in HTML/CSS/JS
- `AgentDashboardPanel` is a thin Swing wrapper hosting `AgentCefPanel`
- 24+ `JBCefJSQuery` bridges for JS→Kotlin communication
- Bolt-style glassmorphic input bar with gradient glow, auto-expand, model/plan/skills chips

JCEF-based (Chromium) rendering with bundled libraries (zero CDN dependency):

**Core (always loaded):** marked.js, Prism.js, DOMPurify, ansi_up
**Lazy-loaded:** dagre.js (flow diagrams), Mermaid.js, KaTeX (math), Chart.js, diff2html

**Animated visualization formats:**
- ```flow — dagre-laid-out SVG with animated particle dots
- ```mermaid — staggered entrance + flowing dash animation
- ```chart — Chart.js with easeOutQuart animation
- ```visualization / ```viz — sandboxed iframe
- ```diff / ```patch — side-by-side diffs via diff2html

**Streaming-aware rendering:** Open code blocks during streaming show skeleton placeholders. `_detectOpenRichBlock()` tracks fence pairs.

**IDE-native features:** click-to-navigate file paths, Jira card embeds, Sonar badges, @ mention autocomplete (files, folders, symbols, tools, skills), toast notifications

**Resource serving:** `CefResourceSchemeHandler` serves from plugin JAR via `http://workflow-agent/` scheme. CSP: `connect-src 'self' http://workflow-agent` (Phase 5 — relaxed from `'none'` to allow the chunked-by-sha256 image upload at `/upload/<sha256>`; still narrow enough that arbitrary external fetches remain blocked).

**Image upload (Phase 5 of multimodal-agent work):**
- `AttachmentUploadHandler` is wired alongside `CefResourceSchemeHandler` in `AgentCefPanel.createBrowser()` via the same `CefSchemeHandlerFactory`. The factory dispatches by URL: `/upload/<sha256>` POSTs go to `AttachmentUploadHandler`, everything else falls through to the static-asset handler.
- The webview's `AttachmentManager` (`agent/webview/src/components/input/AttachmentManager.ts`) computes a sha256 client-side, asks the `_attachmentExists` JCEF bridge whether bytes already live in the active session's `attachments/` dir, and uploads via plain `fetch('http://workflow-agent/upload/<sha256>')` only when needed. Bridge IPC stays text-only — multi-MB binary bytes never go through `JBCefJSQuery`.
- `AttachmentUploadHandler.attachmentStoreProvider` is invoked **per request** so each upload resolves to the *currently active* session's `AttachmentStore` (per-session isolation contract from Phase 4). `AgentCefPanel.currentSessionDirProvider` is the wire that `AgentController` uses to push the active session directory.
- Validation runs on both sides: `AttachmentManager.attachFile` checks size + MIME + per-turn cap client-side (so the user gets an immediate toast with no bridge round-trip); `AttachmentUploadHandler.validate` checks the same again server-side as defense-in-depth. Validation outcome strings (`disabled`, `size_exceeded`, `mime_not_allowed`) are pinned by `AttachmentUploadHandlerTest` because the JS layer branches on them.

**Chat input usage indicator (Phase 7 Task 7.2):**
- `<UsageIndicator>` (`agent/webview/src/components/input/UsageIndicator.tsx`) renders `context: 23K / 132K used (17%)` immediately below the input. Color shifts: gray <50%, amber 50-80%, red >80%. Polls every 1s while mounted; pauses when `document.hidden` so the IDE doesn't burn CPU on unfocused windows. Reads `window.workflowAgent.getContextUsage()` which dispatches into the `_getContextUsage` JCEF bridge.
- Kotlin side: `AgentCefPanel.contextUsageQuery` is wired by `AgentController` to read `(used, max)` from the active `ContextManager`. `used = currentInputTokens()` (`lastPromptTokens` from API or chars/3.5 estimate); `max = maxInputTokensFor(currentModelRef())` from `ModelCatalogService`.
- Defense-in-depth: indicator returns `0/132K` when bridge is missing or throws; never crashes.

**Model picker (Phase 7 Task 7.1):**
- `<ModelPickerRow>` (exported from `InputBar.tsx`) renders the per-row capacity strip + capability badge strip. Capacity: `132K context · 18K per-message`. Badges: 👁 vision · 🔧 tools · 🧠 reasoning · ⚠ deprecated. Source: the `updateModelList` JS bridge payload now carries `contextWindow`, `capabilities`, and `status` fields when `ModelCatalogService` is loaded; legacy payloads without these fields render gracefully (badge strip omitted).
- Kotlin side: `AgentController.loadModelList()` calls `AgentService.getSharedModelCatalog()` to enrich each row. Catalog data is the authoritative source; `isLikelyVisionCapable()` name-heuristic remains as a fallback for cold-catalog cases (so the dropdown is never empty before catalog load).

**Settings-to-JS bridge (Phase 7 followup F-P5-2 / F-P6-1):**
- The React `<InputBar>` no longer hard-codes `IMAGE_DEFAULT_SETTINGS`. On mount and after every `MultimodalSettingsConfigurable.apply()`, `window.workflowAgent.refreshImageSettings()` fetches current `PluginSettings.State` image fields from Kotlin and rebinds the `AttachmentManager` singleton via `__applyImageSettings(json)`. The Configurable uses reflective `AgentControllerRegistry.getController().pushImageSettingsToWebview()` so `:core` doesn't depend on `:agent` (`:agent → :core` is the canonical direction).

## React Webview Architecture

The chat UI is a React + TypeScript app built with Vite, located in `agent/webview/`.

**Stack:** React 19, TypeScript, Zustand (state), Tailwind CSS, Vite

**Build:**
```bash
cd agent/webview && npm run build    # Output: agent/src/main/resources/webview/dist/
```

**Bridge protocol:**
- **Kotlin → JS:** `AgentCefPanel.callJs()` invokes global JS functions registered by `initBridge()` in `jcef-bridge.ts`
- **JS → Kotlin:** `kotlinBridge` object wraps `JBCefJSQuery` bridges injected as `window._xxx` globals

**Key files:**
- `bridge/jcef-bridge.ts` — All bridge function definitions and initialization
- `bridge/globals.d.ts` — TypeScript declarations for Kotlin-injected window globals
- `stores/chatStore.ts` — Primary state: messages, streaming, plans, questions, tool calls
- `stores/themeStore.ts` — IDE theme variables synced from Kotlin

### Chat-tab component layout

- `components/chat/ChatView.tsx` — owns the `MessageList` ref, the post-stream scroll-to-top behavior, the `ScrollButton` overlay, and the `renderItem` switch for finalized messages. Holds **no** footer JSX.
- `components/chat/MessageList.tsx` — single-Virtuoso-root wrapper. Takes a `Footer: ComponentType` and registers it via Virtuoso's `components.Footer` slot. **Never wrap Virtuoso in a flex container**: the parent in `ChatView` is non-flex, so any `flex-1` on a wrapper inside MessageList becomes a dead declaration and collapses the scroller to 0 px. The `Footer` adapter (line ~74) swallows Virtuoso's `ContextProp<>` so a parameter-less Footer component satisfies Virtuoso's typing.
- `components/chat/ChatFooter.tsx` — trailing UI that flows below the last finalized message inside the scrolling region: streaming bubble, active `ToolCallChain`, approval gate, `ProcessInputView`, rollbacks, plan card, `PlanProgressWidget`, question wizard, queued steering messages, `WorkingIndicator`, retry pill, resume banner. **Architectural contract:** stable component (mounted once via `components.Footer`), subscribes to `chatStore` directly, never receives content as a prop. Owns its own `approvalRef` / `questionsRef` and the `scrollIntoView` effects. Reason for the no-prop rule: Virtuoso's urx store does not reliably forward fresh props to its Footer slot under high-frequency streaming updates — a regression from `fce96d1df` confirmed empirically; pinned by `__tests__/message-list.test.tsx`.
- `components/chat/WorkingIndicator.tsx` — the working indicator + `WORKING_PHRASES` + `useTypewriter`. Lives in its own file because of the 220-line phrase list.

## Streaming Text Pipeline

Three layers between raw SSE token and rendered DOM:

1. **ThinkingTagSplitter** (Kotlin, `agent/ui/ThinkingTagSplitter.kt`): pure state machine that pulls `<thinking>...</thinking>` blocks out of the assistant text. Holds back potential tag-prefix bytes across chunk boundaries (`<thi` arriving alone doesn't leak as text). Three Part variants: `Text` for prose, `ThinkingDelta(text)` for incremental thinking bytes (emitted live as each chunk arrives — same cadence as prose, NOT buffered to the close tag), and `ThinkingEnd` to mark the close of a non-empty block. Text parts continue through `streamBatcher`; `ThinkingDelta` routes via `dashboard.appendToThinking()` → JS `appendToThinking` → `chatStore.appendToThinking()` → live render in `ChatFooter` as `<ThinkingView isStreaming={true}>` (prompt-kit `Reasoning` + `TextShimmer`, no auto-collapse). `ThinkingEnd` routes via `dashboard.endThinking()` → JS `endThinking` → `chatStore.endThinking()` which flushes the accumulated buffer into `messages` as a `say: 'REASONING'` UiMessage rendered by `ChatView.renderItem` as `<ThinkingView isStreaming={false}>` (auto-collapses after 600 ms, user can re-expand). Lockstep flush/reset with `streamBatcher` via `AgentController.flushStream()` / `clearStream()`. Fallback (non-JCEF) panel still receives the full block one-shot via `RichStreamingPanel.appendThinking()` — `AgentDashboardPanel` accumulates deltas in `thinkingFallbackBuffer` and calls fallback only on `endThinking()` when `cefPanel == null`.
2. **StreamBatcher** (Kotlin, `agent/ui/StreamBatcher.kt`): 16ms EDT timer coalesces rapid chunks into single bridge calls (~5000 → ~300 per response).
3. **chatStore streaming-message model**: `appendToken()` creates/updates a placeholder `Message` in-place. `endStream()` just clears `activeStream`. Same `AgentMessage` component renders both streaming and finalized — no mount/unmount flash.

**Incomplete code fences** render as plain `<pre class="streaming-code-plain">` until closed, then swap to Shiki-backed `CodeBlock`.
**Module-scope invariant:** `MarkdownRenderer.tsx` declares `COMPONENTS`, `REMARK_PLUGINS`, `REHYPE_PLUGINS` at module scope (inline literals defeat Streamdown's per-block `React.memo`).

**Partial tool-call hook (streaming edit preview):** the per-chunk `AssistantMessageParser.parse` output is also fed into `StreamingEditTracker.observe(callId, params, isPartial)` for every `ToolUseContent` whose `name == "edit_file"`. Gated by `PluginSettings.enableStreamingEditPreview` (default on) — see "Streaming Edit Preview" below.

## Streaming Edit Preview

Live unified-diff preview for `edit_file` tool calls — the chat panel renders the diff growing inside the chat while the LLM is still emitting `<new_string>`, instead of waiting until the full tool call arrives and an approval card appears.

**Pipeline:**

1. `AgentLoop.onChunk` re-parses the SSE buffer (existing logic) → `List<AssistantMessageContent>`.
2. For each `ToolUseContent` block where `name == "edit_file"`, the loop calls `streamingEditTracker.observe(callId, params, isPartial)`. `callId` is a stable per-loop-iteration block-index key (`"edit-{iter}-{idx}"`); deterministic across re-parses because the parser is deterministic over the accumulated buffer.
3. `StreamingEditTracker` (in `preview/StreamingEditTracker.kt`) decides:
   - **First sighting** with both `old_string` AND `new_string` keys present in params (the parser opens `new_string` only after `</old_string>` closes) → runs `EditFileTool.preview()` (reused from Commit 1) to validate. On any validation failure (path traversal, file missing, no match, ambiguous match without `replace_all`) the preview is silently dropped; `execute()` will surface the precise error when the tool call completes. On `EditPreview.Ready` → snapshots the original content, fires `onOpen(callId, path, realDiff)`.
   - **Update** (`isPartial=true` after first sighting) → throttled to 100ms ticks (configurable). Identical `new_string` across re-parses is suppressed. Each accepted update rebuilds the unified diff (`originalContent` with `old_string → new_string`) and fires `onUpdate(callId, diff)`.
   - **Finalize** (`isPartial=false`) → pushes final diff via `onUpdate`, fires `onFinalize(callId)`, marks state as finalized. Subsequent stray re-parses for the same id are no-ops.
4. Callbacks fan out via `StreamingEditCallback` (typed interface in `loop/AgentLoop.kt`) — `AgentController` implements it as four JSON-encoded `dashboard.callJs("if (window._streamingEdit{Open|Update|Finalize|Cancel}) {...}")` pushes.
5. JCEF bridge (`webview/src/bridge/jcef-bridge.ts`) parses the JSON args and dispatches into `chatStore.streamingEdit{Open|Update|Finalize|Cancel}`.
6. `chatStore.streamingEdits: Record<callId, {path, diff, status}>` projection rendered by `<StreamingEditPreviewView />` mounted inside `ChatFooter`. Empty map → nothing renders.

**Lifecycle:**
- `AgentLoop.cancel()` calls `streamingEditTracker?.cancelAll()` — every open preview drops cleanly via `onCancel(callId)`. The real file is never touched during streaming, so cancellation is purely a UI affordance.
- `chatStore.endStream()` resets `streamingEdits` to `{}`. By the time the approval card appears for an `edit_file`, the streaming preview is already gone — the approval card shows the same diff statically. Users never see two cards rendering the same diff.
- `clearChat()` / `startSession()` / `completeSession()` also reset `streamingEdits`.

**Feature flag:** `PluginSettings.enableStreamingEditPreview` (default `true`). Read once per AgentLoop iteration; checked together with `streamingEditTracker != null` to gate the observe call inside `onChunk`. Sub-agents and tests pass `streamingEditCallback = null` → no tracker constructed, zero overhead. Settings UI lives in `AgentAdvancedConfigurable` → "AI Agent ▸ Advanced ▸ Tool Calling".

**Invariants pinned by tests:**
- `StreamingEditTrackerTest` — 10 behavioural cases (validation failure paths, throttle, finalization, cancellation, real-file hunk-offset anchoring).
- `AgentLoopStreamingEditTest` — 3 source-text pins: `streamingEditTracker.observe` is in `onChunk`; the `enableStreamingEditPreview` flag gates it; `cancel()` calls `cancelAll()`.
- `StreamingEditPreviewView.test.tsx` — 7 React cases (empty map → null, multi-entry, live indicator on `streaming`, no indicator on `finalized`, path display, `DiffHtml` receives the diff, cancel removes the card).

Why a tracker rather than push-on-execute: this mirrors Cline's `DiffViewProvider` UX but routes through the JCEF chat panel rather than a separate IntelliJ diff editor. Editor-pane streaming can be a follow-up.

## Cross-IDE Delegation (doorbell consent model — Plan 6)

Plan 6 adds a consent path for inbound-OFF targets. Two sockets per project:

| | Delegation socket (`DelegationPaths.socketFor`) | Doorbell socket (`DelegationPaths.doorbellSocketFor`) |
|---|---|---|
| Path | `<hash>.sock` | `<hash>.doorbell.sock` |
| Bound when | inbound setting ON or transient grant | **always**, every open project, on plugin load |
| Accepts | full work protocol | **only** `Knock` |
| Can start a session? | yes | **no** — raises a consent dialog only |

**`DelegationDoorbellService`** (`@Service(PROJECT)`) binds `doorbellSocketFor` at project open via `DelegationDoorbellStartupActivity` (registered as `<postStartupActivity>`). On `Knock`: dedupe by nonce, rate-limit ≤ 1 dialog per delegator per 10 s, reply `KnockAck(RINGING)` immediately (before showing the dialog so IDE A's `knock()` returns promptly), then raise `DelegationInboundConsentDialog` on EDT.

**`DelegationInboundConsentDialog`** — three exits:
- **Allow once** → `DelegationInboundService.startTransient()` binds the delegation socket WITHOUT persisting the setting; calls `recordPreauth(nonce)`. Delegation socket unbinds when no active delegated sessions remain (`stopIfTransientAndIdle(count)`). State does not survive IDE restart.
- **Allow always** → sets `PluginSettings.enableInboundCrossIdeDelegation = true` (fires `CrossIdeDelegationSettingsListener.inboundSettingChanged(true)` → existing `start()`); calls `recordPreauth(nonce)`.
- **Cancel** → `PendingDelegationStore.markDeclined(nonce)`; IDE A's `AutoLaunchPoller` bail-out sees `isDeclined(nonce)` and throws `DelegationException.Rejected("inbound_consent_declined")`.

**Preauth nonce** (`Connect.preauthNonce`) — single-use gate. `DelegationInboundService.recordPreauth(nonce)` + `consumePreauth(nonce)` (atomic `ConcurrentHashMap.remove`). When the nonce matches, `handleConnect` skips the `AcceptDelegationDialog`. Non-matching or null nonce falls back to the normal Accept dialog.

**Fresh-launch path** — IDE A writes `pending-delegation/<nonce>.json` into IDE B's agent dir before launching. After smart mode, `DelegationDoorbellStartupActivity.replayPendingRequests()` scans `PendingDelegationStore.readFresh(ttl=5min)`, dedupes against any nonce already handled live, and raises the consent dialog for each.

**Already-running path** — IDE A calls `DelegationClient.knock(doorbellSocketFor(target), Knock(..., nonce))`. A `KnockAck(RINGING|DUPLICATE)` means IDE B is up and a dialog is visible; null means IDE B is not running.

**Outbound flow** (`DelegationOutboundService.send()`): ping `socketFor(B)` first; if reachable → existing path. If unreachable: generate nonce → write pending file → `knock(doorbellSocketFor(B))` → (no answer) spawn launcher → `AutoLaunchPoller` waits for socket-or-declined → on bind, `Connect(preauthNonce = nonce)`.

**Transient teardown** — wired in `AgentService`: when a delegated session ends, calls `DelegationInboundService.stopIfTransientAndIdle(activeDelegatedSessionCount)`. If `transient && count == 0`, `stop()` unbinds and resets `transient = false`.

**Tests** — `PendingDelegationStoreTest`, `DelegationPreauthConnectTest`, `DelegationDoorbellServiceTest`, `DelegationKnockFlowTest`, `DelegationE2ETest` (inbound-off → consent → work happy path).

### Live delegated-session banner (IDE-B top bar, 2026-05-30)

When IDE-B starts a delegated session, `AgentController.pushActiveSessionDelegated(metadata)` (in `runDelegatedNow.onSessionStarted`) drives the `_setActiveSessionDelegated` JCEF bridge → `chatStore.activeSessionDelegated` → the top-bar `DelegationBanner` ("Delegated by {IDE} from {repo}"). Cleared (`null`) in `resetForNewChat`. Previously the banner only populated when a delegated session was reopened from history. Tests: `delegation-banner-bridge.test.tsx`.

### Transcript retrieval + status (2026-05-30 rework)

`fetch_transcript` now reads IDE-B's `api_conversation_history.json` **directly off the shared filesystem** (Unix sockets ⇒ same host) instead of an IPC round-trip — fixing two live-reported bugs: the old path sent IDE-A's session id to IDE-B ("no conversation history on disk"), and the handle was torn down the instant the result arrived ("handle_not_found" ~83 s post-completion). `DelegationOutboundService.close()` now snapshots a `RetainedHandle` (bSessionId, targetPath, repoName, lastState) kept for `TRANSCRIPT_RETENTION_MILLIS` (30 min), so post-completion fetch + the new `status` action both work. Inbound `runInboundReadLoop` uses the channel's own `localSessionId` (authoritative), not the remote-supplied `msg.sessionId`. New `delegation(action="status")` returns active/closed/unknown without a transcript round-trip. Tests: `DelegationTranscriptRetentionTest`, `DelegationStatusActionTest`.

### Async result auto-delivery + explicit wait (2026-05-30)

**Auto-delivery fix.** A delegation result that arrives after IDE-A's orchestrator turn already ended was silently dropped — `enqueueNudgeForSession` only had an active-loop branch (`enqueueSteeringMessage`); the idle case logged "nudge dropped". The normal post-`send` state IS idle (the LLM completes its turn after delegating), so single-delegation results routinely vanished. Fixed by routing the idle case through the **same persist+auto-wake mechanism background-process completion uses**: `enqueueNudgeForSession` → `autoWakeIdleSession` (shared helper, also now used by `autoResumeForBackgroundCompletion`) → guarded by `autoWakeGuards` + `AgentSettings.autoWakeOnBackgroundCompletion` → resumes the session with a `[DELEGATION RESULT — AUTO-RESUMED]` synthetic message. The pure routing decision is `idleWakeRoute(decision, listenerPresent)` (unit-testable without constructing `AgentService`). Tests: `DelegationIdleWakeRoutingTest` + the unchanged background `BackgroundCompletionSteeringTest`/`AutoWakeGuardStateTest`.

**Explicit `wait`.** `delegation(action="wait", handle, timeout_seconds?)` blocks the current turn until the delegation completes or raises a question, returned inline (default 300 s, 5–1800). Implemented via `DelegationOutboundService.awaitResult` + a `pendingResultWaiters` one-shot deferred per handle: the reader loop completes the waiter on `Result`/`Question` (and suppresses the async nudge for that handle), with a `finally` safety-net so a channel close can't leave `wait` hanging. `DelegationTool.timeoutMs = Long.MAX_VALUE` so the loop's 120 s per-tool timeout doesn't truncate a legitimate wait (the action is self-bounded by its own `timeout_seconds`). A wait timeout is NOT a failure — the async auto-delivery still fires. Tests: `DelegationWaitTest`, `DelegationWaitActionTest`.

### IDE-B (inbound) UI-callback parity — `SessionUiCallbacks` bundle (2026-06-01)

A delegated session on IDE-B runs through the SAME `executeTask`/`resumeSession` machinery as a normal session (approval gate, hooks, checkpoints, network recovery, compaction, TaskStore, metrics — all already correct). The ONLY divergence was that the delegated entry points (`AgentController.runDelegatedNow` / `runResumedDelegatedNow` → `AgentService.startDelegatedSession` / `resumeDelegatedSession`) hand-forwarded only ~7 of the ~24 controller→loop UI callbacks the interactive path wires — so every callback added after the delegated wrappers were written (subagent cards, token/stats chips, retry pill, compaction overlay, model-switch chip, streaming-edit preview, handoff card) silently went dark on the delegated path.

**Structural fix — single source of truth + parity lock.** `agent/ui/SessionUiCallbacks.kt` is a `data class` holding the full controller→loop UI-callback set. `AgentController.buildSessionUiCallbacks()` is the ONE builder; `executeTaskInternal`, `runDelegatedNow`, and `runResumedDelegatedNow` ALL source the callbacks from it (the delegated callers `.copy()` only two overrides — see #1 and #5 below). `startDelegatedSession`/`resumeDelegatedSession` accept the bundle and forward every field into `executeTask`/`resumeSession`. A future callback added to the builder flows to both paths automatically. Pinned by `SessionUiCallbacksParityTest` (fails if any bundle field is dropped on either delegated seam) + `DelegatedSubagentProgressWiringTest`.

- **#1 single terminal card + spinner finalize.** Wiring `onComplete` would make the generic completion card fire AND the delegation result card fire (two terminal cards). Resolved: the delegated path `.copy(onComplete = ::delegatedFinalizeOnComplete)` — that handler does the spinner / active-tool-chain / `setBusy(false)` finalize (so the session no longer "looks stuck") but SUPPRESSES the generic `completeSession`/`appendCompletionCard`. The repo-named delegation RESULT card (`wrapDelegatedOnResult` → `pushDelegatedResult` on the socket `onResult`) is the single terminal card. Socket result delivery is UNCHANGED: AgentService still completes `loopResultDeferred` and calls `onResult`; the bundle's `onComplete` is *chained* before `loopResultDeferred.complete(result)`, never replacing it.
- **#5 delegated session is ACT-ONLY.** Rather than wire an interactive plan-approval surface over a remotely-driven session, `AgentService`'s `toolDefinitionProvider` filters out BOTH plan tools (`enable_plan_mode` + `plan_mode_respond`) when `currentSessionState()?.delegated != null` (the marker both delegated entry points stamp before the loop starts) — mirroring sub-agents' `includePlanModeSection=false`. The plan callbacks in the bundle are simply never exercised on the delegated path. Pinned by `DelegatedActOnlyToolFilterTest`.
- **#7/#8 intentionally NOT wired (documented).** Inbound delegated tasks bypass the local `USER_PROMPT_SUBMIT` hook on purpose — the prompt originates from a REMOTE IDE, not a local keystroke (the hook runs only in `executeTaskInternal`). Local mid-turn STEERING of a delegated session is unsupported — cross-IDE interaction flows through the routed question/answer channel (`DelegationInboundService`), not the local steering queue. The bundle's `steeringQueue` is still forwarded (harmless; no local typing path feeds a delegated session), so nothing is lost. See the comment in `runDelegatedNow`.

---

## Testing

```bash
./gradlew :agent:test                    # All agent tests (~112 test files)
./gradlew :agent:test --tests "...Test"  # Specific test class
./gradlew :agent:clean :agent:test --rerun --no-build-cache  # Clean rebuild
```

Key test patterns: JUnit 5 + MockK + `@TempDir` for file I/O, `runTest` for coroutines, `mockk<Project>` for IntelliJ services.

**Read-action shim.** Tests that exercise code calling `readAction { }` / `smartReadAction(project) { }` / `readActionBlocking { }` must call `installReadActionInlineShim()` from `testutil/ReadActionTestShim.kt` in `@BeforeEach`, paired with `unmockkAll()` in `@AfterEach`. The real builders resolve `ApplicationManager.getApplication().getService(ReadWriteActionSupport::class.java)` and NPE without a live `Application`; the shim invokes lambdas inline on the calling coroutine. This replaces the pre-Phase-4 `mockkStatic(ReadAction::class)` pattern.

## IntelliJ-Native APIs

Core tools (read, edit, search) use IntelliJ Document API and VFS for undo support, unsaved change visibility, and editor sync.

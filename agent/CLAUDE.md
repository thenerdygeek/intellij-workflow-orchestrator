# :agent Module

AI coding agent with ReAct loop, LLM-controlled delegation, interactive planning, and ~80 tools.

## LLM API

Uses Sourcegraph Enterprise's OpenAI-compatible API:
- Endpoint: `/.api/llm/chat/completions`
- Auth: `token` scheme via `Authorization: token <sourcegraph-access-token>`
- Constraints: 190K input tokens (configurable), no `system` role (converted to user with `<system_instructions>` tags), no `tool_choice`, strict user/assistant alternation
- Output limit varies per model — no hardcoded clamp. User configures maxOutputTokens in settings.
- Model: Auto-resolved from `GET /.api/llm/models` on first use via `ModelCache` (in `:core`). Priority: Anthropic Opus thinking > Opus > Sonnet. No hardcoded defaults.
- Message sanitization in `SourcegraphChatClient.sanitizeMessages()` (in `:core`): system→user, tool→user with plain text prefix "RESULT of {toolName}:" (not XML — prevents LLM echo hallucination), consecutive same-role merging, zero-width space for empty assistant tool-call messages

## Architecture

```
AgentController (UI entry point, owns AgentCefPanel + JCEF bridges)
  → AgentService (orchestration, tool registration, session management)
    → AgentLoop.run() (ReAct loop, maxIterations=200)
      → ContextManager (3-stage compaction: dedup → truncation → LLM summarization)
      → LoopDetector (doom loop detection: 3 soft warning, 5 hard failure)
      → Tool execution with optional approval gate
      → Steering messages (ConcurrentLinkedQueue, drained at iteration boundary)
```

## Key Components

- **AgentLoop** (`loop/AgentLoop.kt`, ~1471 lines) — Core ReAct loop. Tool call processing, context compaction on overflow, parallel read-only tool execution (via coroutineScope+async), sequential write tool execution. Truncated tool call recovery — detects invalid JSON when finishReason=length, asks LLM to retry with smaller operation. Mid-loop cancellation support. Context overflow replay (compress + retry same request). Doom loop detection before each tool call. Streaming token estimate when usage is null. Plan mode execution guard (blocks `WRITE_TOOLS` even if LLM hallucinates them).
- **AgentService** (`AgentService.kt`, ~1549 lines) — Main orchestration service. Manages tool registration, session lifecycle, plan mode state (`planModeActive: AtomicBoolean`), model fallback, context management setup. Builds `AgentLoop` with all callbacks wired. Schema filtering for plan mode (removes write tools + `enable_plan_mode` from tool definitions before LLM call). Dynamic tool definition provider rebuilds system prompt when tool set changes.
- **AgentController** (`ui/AgentController.kt`) — UI entry point. Owns `AgentCefPanel`, routes user messages, handles steering, dispatches JCEF bridge callbacks (tool approval, plan approval, session history, artifact results). Wires `AgentService.executeTask()` with UI callbacks.
- **ContextManager** (`loop/ContextManager.kt`, ~891 lines) — 3-stage compaction pipeline ported from Cline:
  - Stage 1: Duplicate file read detection — tracks files read by path, replaces older reads with placeholder, keeps most recent read. If savings ≥ 30%, stop here.
  - Stage 2: Conversation truncation (Cline's `getNextTruncationRange`) — preserves first user-assistant exchange and last N messages, removes middle messages in even-count blocks.
  - Stage 3: LLM summarization (our addition) — summary chaining includes previous summary in next compaction, inserts summary as assistant message.
  - Tracks active skill content, TaskStore reference, file read indices.
- **LoopDetector** (`loop/LoopDetector.kt`, ~118 lines) — Detects identical consecutive tool calls. 3 identical = soft warning injected as system message. 5 identical = hard failure stops the loop.
- **MessageStateHandler** (`session/MessageStateHandler.kt`, ~302 lines) — Two-file JSON persistence (api_conversation_history.json + ui_messages.json). Atomic file writes via write-then-rename, per-session `kotlinx.coroutines.sync.Mutex`.
- **SessionLock** (`session/SessionLock.kt`) — `java.nio.channels.FileLock` on `.lock` file to prevent dual-instance access.
- **ToolRegistry** (`tools/ToolRegistry.kt`, ~229 lines) — Three-tier registry: core (always sent to LLM), deferred (available via `tool_search`), active-deferred (loaded during session). Reduces per-call schema tokens from ~10K to ~4K.
- **SystemPrompt** (`prompt/SystemPrompt.kt`, ~507 lines) — Builds the system prompt per turn. 11 sections following Cline's generic variant template: Agent Role → Task Management → Editing Files → Act vs Plan Mode → Capabilities → Skills → Deferred Tool Catalog → Rules → System Info → Objective → Memory → User Instructions.
- **InstructionLoader** (`prompt/InstructionLoader.kt`, ~453 lines) — Loads skill and agent config files from resources and disk. Handles YAML frontmatter parsing, substitution variable expansion (`$ARGUMENTS`, `$1`-`$N`, `${CLAUDE_SKILL_DIR}`). Dynamic injection via `` !`command` `` for preprocessing.
- **SpawnAgentTool** (`tools/builtin/SpawnAgentTool.kt`) — Primary tool for spawning subagents. Only `description` and `prompt` required. Optional `name` makes agents addressable for resume/send. `subagent_type` selects built-in or custom agents. Defaults to general-purpose. Explorer type restricted to read-only tools.
- **SubagentRunner** (`tools/subagent/SubagentRunner.kt`) — Executes subagent with isolated context and budget. `buildComposedSystemPrompt()` appends a standard "COMPLETING YOUR TASK" section (via `COMPLETING_YOUR_TASK_SECTION` constant) to every sub-agent's composed system prompt so all personas know to call `task_report`, not `attempt_completion`.
- **ModelFallbackManager** (`loop/ModelFallbackManager.kt`) — Opt-in model fallback. On NETWORK_ERROR/TIMEOUT, advances through fallback chain (Opus thinking → Opus → Sonnet thinking → Sonnet). After 3 successful iterations on fallback, attempts escalation back to primary.
- **HookManager** (`hooks/HookManager.kt`) — 8 lifecycle hook types (TaskStart, UserPromptSubmit, TaskResume, PreCompact, TaskCancel, PreToolUse, PostToolUse, TaskComplete). Config: `.agent-hooks.json` in project root.
- **AttemptCompletionTool** (`attempt_completion`) — Explicit completion signal for the **orchestrator**. LLM must call this to end the session. Text-only responses (no tool calls) trigger escalating nudges (up to `MAX_NO_TOOL_NUDGES=4`) demanding `attempt_completion`. `allowedWorkers = {ORCHESTRATOR}` only.
- **TaskReportTool** (`task_report`) — Completion signal for **sub-agents** (replaces `attempt_completion` at the sub-agent boundary). Forces the sub-agent to produce a structured report (summary, findings, files, next_steps, issues) that flows directly into the parent LLM's tool result — unlike `attempt_completion`, which targets the user UI. `allowedWorkers = {CODER, REVIEWER, ANALYZER, TOOLER}`. Auto-injected by `SpawnAgentTool.resolveConfigToolsTiered()`; any `attempt_completion` in a config's `tools:` field is silently dropped and replaced by this tool.
- **StreamBatcher** (`ui/StreamBatcher.kt`) — 16ms EDT timer coalesces rapid SSE chunks into single JCEF bridge calls (~5000 → ~300 per response).

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
10. **Memory** — 3-tier memory explanation, manual tool guidelines, recalled memory usage
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
| `attempt_completion` | AttemptCompletionTool | Explicit task completion signal (orchestrator only) |
| `task_report` | TaskReportTool | Sub-agent completion: structured findings report for parent LLM (sub-agents only, auto-injected) |
| `think` | ThinkTool | No-op reasoning scratchpad |
| `ask_followup_question` | AskQuestionsTool | Ask user questions (simple or wizard mode) |
| `plan_mode_respond` | PlanModeRespondTool | Present plan in plan mode |
| `enable_plan_mode` | EnablePlanModeTool | Switch to plan mode |
| `use_skill` | UseSkillTool | Load and activate a skill |
| `new_task` | NewTaskTool | Session handoff with structured context |
| `render_artifact` | RenderArtifactTool | Interactive React component in chat |
| `find_definition` | FindDefinitionTool | PSI go-to-definition |
| `find_references` | FindReferencesTool | PSI find usages |
| `diagnostics` | SemanticDiagnosticsTool | IDE error/warning diagnostics |
| `tool_search` | ToolSearchTool | Search and activate deferred tools |
| `agent` | SpawnAgentTool | Spawn/resume/send/kill subagents |

**Conditional core** (registered when memory/storage is initialized):
- `core_memory_read`, `core_memory_append`, `core_memory_replace` — core memory tools
- `archival_memory_insert`, `archival_memory_search` — archival memory tools
- `conversation_search` — past session search
- `save_memory` — legacy markdown memory

### Deferred Tools (loaded via `tool_search`)

| Category | Tools |
|----------|-------|
| Code Intelligence | find_implementations, file_structure, type_hierarchy, call_hierarchy, type_inference, dataflow_analysis, get_method_body, get_annotations, test_finder, structural_search, read_write_access, project_structure (resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets, refresh_external_project, add_source_root, set_module_dependency, remove_module_dependency, set_module_sdk, set_language_level, add_content_root, remove_content_root) |
| Code Quality | format_code, optimize_imports, refactor_rename, run_inspections, problem_view, list_quickfixes |
| VCS | changelist_shelve |
| Build & Run | build, spring, django, fastapi, flask, runtime_exec, java_runtime_exec (Java only), python_runtime_exec (Python only), runtime_config, coverage |
| Database | db_list_profiles, db_list_databases, db_query, db_schema, db_stats, db_explain |
| Utilities | project_context, current_time, kill_process, send_stdin, ask_user_input |
| Debug | debug_step, debug_inspect, debug_breakpoints |
| Integration (conditional) | jira, bamboo_builds, bamboo_plans, sonar, bitbucket_pr, bitbucket_repo, bitbucket_review |

Integration tools are only registered when their service URL is configured in ConnectionSettings.

### Meta-Tools (single tool with `action` parameter)

These tools consolidate multiple operations behind an `action` enum:

| Meta-Tool | Action Count | Examples |
|-----------|-------------|----------|
| **runtime_exec** | 3 | get_test_results, get_running_processes, get_run_output (universal observation). `get_test_results` unified through `interpretTestRoot` — empty suites return `NO_TESTS_FOUND` (not `PASSED`) and failed runs propagate `isError=true`. |
| **java_runtime_exec** | 2 | run_tests (JUnit/TestNG), compile_module (CompilerManager) — registered only when Java plugin present. **run_tests runs a pre-flight `BuildSystemValidator` check** (Phase 2 Tasks 2.7–2.9) before any dispatch — catches main-sources classes, zero @Test methods, missing `settings.gradle` entries, and unregistered Maven modules with actionable suggestions. Authoritative Gradle/Maven paths from the validator supersede filesystem-derived subproject paths in the shell fallback; a breadcrumb (`Running tests in module: X (Build path: Y, N test methods detected in Z)`) is prepended to every successful result. **run_tests explicitly calls `ProjectTaskManager.build(module)` before launching JUnit** (commit 9b164bf3 guard: config is transient/unregistered so factory-default "Build" before-run task is NOT wired; we call the build API ourselves to prevent `initializationError`). On build failure, `run_tests` returns per-file compile errors (path:line:col — message) via `formatCompileErrors` instead of a bare count. Shell fallback parses Surefire/Gradle XML reports and returns `NO_TESTS_FOUND` on empty suites (not `PASSED`). |
| **python_runtime_exec** | 2 | run_tests (pytest via PytestNativeLauncher → PyTestConfigurationType native runner first, shell-based PytestActions fallback), compile_module (python -m py_compile) — registered only when Python plugin present. Native runner integrates with PyCharm's test UI; fallback covers non-PyCharm IDEs. Pytest output reconciles verbose PASSED/FAILED/ERROR counts against the summary line and emits a heuristic warning when the suite reports passes with near-zero duration and no stdout. |
| **runtime_config** | 4 | get_run_configurations, create/modify/delete_run_config |
| **coverage** | 2 | run_with_coverage, get_file_coverage. `run_with_coverage` unified through `interpretTestRoot`; failed coverage runs propagate `isError=true`. |
| **debug_breakpoints** | 8 | add_breakpoint, method_breakpoint, exception_breakpoint, field_watchpoint, remove_breakpoint, list_breakpoints, start_session, attach_to_process |
| **debug_step** | 8 | get_state, step_over, step_into, step_out, resume, pause, run_to_cursor, stop |
| **debug_inspect** | 8 | evaluate, get_stack_frames, get_variables, thread_dump, memory_view, hotswap, force_return, drop_frame |
| **spring** | 15 | context, endpoints, bean_graph, config, version_info, profiles, etc. |
| **build** | 11 | maven_dependencies, gradle_tasks, project_modules, module_dependency_graph, etc. |
| **project_structure** | 14 | resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets, refresh_external_project, add_source_root, set_module_dependency, remove_module_dependency, set_module_sdk, set_language_level, add_content_root, remove_content_root |
| **jira** | 17 | get_ticket, search_issues, transition, comment, log_work, etc. |
| **bamboo_builds** | 11 | build_status, trigger_build, get_build_log, get_test_results, etc. |
| **bamboo_plans** | 8 | get_plans, search_plans, get_plan_branches, rerun_failed_jobs, etc. |
| **sonar** | 13 | issues, quality_gate, coverage, branch_quality_report, local_analysis, etc. |
| **bitbucket_pr** | 14 | create/approve/merge/decline_pr, get_pr_detail, check_merge_status, etc. |
| **bitbucket_review** | 6 | add_pr_comment, add_inline_comment, reply_to_comment, etc. |
| **bitbucket_repo** | 6 | get_branches, create_branch, search_users, get_file_content, etc. |

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

All context management runs through `ContextManager` — a 3-stage compaction pipeline ported from Cline.

### Pipeline

1. **Stage 1 — Duplicate file read detection** (from Cline): Tracks files read by path. Replaces older reads with `"[File content for '{path}' — see latest read below]"`. Keeps most recent read. If savings ≥ 30%, stop here.
2. **Stage 2 — Conversation truncation** (from Cline's `getNextTruncationRange`): Preserves first user-assistant exchange (task description) and last N messages (recent work). Removes middle messages in even-count blocks to maintain user-assistant role alternation.
3. **Stage 3 — LLM summarization** (our addition — Cline doesn't have this): Optional fallback when truncation alone isn't enough. Summary chaining: includes previous summary in next compaction. Inserts summary as assistant message to avoid consecutive user messages.

### Key details
- **Compaction threshold**: 85% of `maxInputTokens` (default 150K)
- **Token tracking**: `lastPromptTokens` from API response, invalidated after compaction
- **Tool output**: Full content in context. `truncateOutput()` middle-truncates at 50KB (60% head + 40% tail).
- **Active skill**: Stored in `ContextManager`, re-injected into system prompt after compaction
- **Task store**: `ContextManager.attachTaskStore(TaskStore)` wires the task store; current tasks rendered into system prompt Section 2 after every compaction rebuild
- **History overwrite callback**: After compaction, `onHistoryOverwrite` persists modified conversation via `MessageStateHandler`

## TaskStore + Task Tools

> See `docs/plans/2026-04-18-task-system-port.md` for the port plan and `docs/research/2026-04-18-claude-code-task-system-research.md` for the design research.

Four hook-exempt tools let the LLM manage typed tasks within a session:

| Tool | Purpose |
|---|---|
| `task_create` | Create a task with title, description, optional `blocks`/`blockedBy` dependencies |
| `task_update` | Update status (`TODO`/`IN_PROGRESS`/`DONE`/`BLOCKED`), title, or description |
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
- **Write tools set** (`WRITE_TOOLS` in `AgentLoop`): edit_file, create_file, run_command, revert_file, kill_process, send_stdin, format_code, optimize_imports, refactor_rename
- **Approval tools** (`APPROVAL_TOOLS`): edit_file, create_file, run_command, revert_file — require user approval unless already allowed for session
- **Per-tool timeouts**: `withTimeoutOrNull` wraps every execution — default 120s, `run_command` 600s, `agent` (SpawnAgentTool) unlimited. Timeout returns an error ToolResult; the LLM can retry or adjust.
- **RunCommandTool component architecture**:
  - **ShellResolver** (`tools/command/ShellResolver.kt`) — Platform-aware shell detection. Windows: Git Bash → PowerShell → cmd. Unix: `/bin/bash` → `$SHELL` → `/bin/sh`. macOS/Linux uses `/bin/bash` (not `sh` which is `zsh` on macOS). Login shell `-l` flag for PATH recovery.
  - **DefaultCommandFilter** (`security/DefaultCommandFilter.kt`) — Hard-block pre-spawn patterns: fork bombs, `rm -rf /`, `sudo`, `mkfs`, etc. Returns rejection before process is created.
  - **OutputCollector** (`tools/command/OutputCollector.kt`) — 50/50 line-based head/tail truncation, disk spill for large outputs, Unicode sanitization.
  - **ProcessEnvironment** (`tools/command/ProcessEnvironment.kt`) — Strips 35+ sensitive vars (credentials, tokens, keys), blocks 25 vars from the LLM-provided `env` parameter, applies 15 anti-interactive overrides (TERM, PAGER, GIT_TERMINAL_PROMPT, etc.).
- **RunCommandTool parameters**: `command` (required), `cwd` (optional), `env` (optional JSON object for LLM-provided environment variables, filtered through security blocklist), `separate_stderr` (optional boolean for separate stderr capture).
- **Two distinct safety layers for commands**: (1) `DefaultCommandFilter` — hard-block patterns, pre-spawn, in `security/` package; (2) `CommandSafetyAnalyzer` — risk classification for the approval gate, called in `AgentLoop` not in `RunCommandTool`.
- **CancellationException**: Always re-thrown (never swallowed) so coroutine scope cancellation propagates correctly through the loop.
- **Registration failures**: Tracked and logged; no more silent swallowing of deferred activation errors.
- **Token estimate**: Computed after output truncation, not before, so context fill is accurate.
- **Doom loop detection**: 3 identical consecutive tool calls = warning. 5 = hard failure.
- **Context overflow**: Compress via `ContextManager` + REPLAY the failed request (OpenCode pattern)
- **Task system**: Managed via `TaskStore` (four tools: `task_create`, `task_update`, `task_list`, `task_get`). Tasks are hook-exempt (bypass PreToolUse/PostToolUse). Progress rendered into system prompt Section 2 from `ContextManager.attachTaskStore`.
- **Dumb mode checks**: `OptimizeImportsTool` and `FormatCodeTool` check `DumbService.isDumb(project)` before operating — prevents removing used imports during indexing
- **Session-scoped state**: `EditFileTool.lastEditLineRanges` keyed by `sessionId:canonicalPath` to prevent cross-session contamination of diagnostics edit ranges
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
- **System prompt guidance**: RULES section instructs the LLM on when to use `grep_pattern` (targeted extraction), `output_file` (save for later), and to prefer dedicated tools over raw commands.

## Plan Mode Enforcement

Two-layer enforcement:

1. **Schema filtering** (`AgentService` tool definition provider, ~line 834) — Removes write tools and `enable_plan_mode` from tool definitions before each LLM call. The LLM never sees blocked tools. In act mode, removes `plan_mode_respond`.
2. **Execution guard** (`AgentLoop.run()`, ~line 955) — Checks `planMode && toolName in WRITE_TOOLS` as a safety net for cached tool calls from before mode switch.

**Blocked in plan mode:** edit_file, create_file, run_command, revert_file, kill_process, send_stdin, format_code, optimize_imports, refactor_rename, enable_plan_mode
**Always available:** read_file, search_code, glob_files, diagnostics, find_definition, find_references, think, agent, tool_search, ask_followup_question, plan_mode_respond, memory tools, etc.

**Transition:** `AgentService.planModeActive.set(false)` → tools restored on next LLM call → dashboard UI updated.

## Error Handling

- **API retry**: 5 attempts, exponential backoff with jitter (base 1s, max 30s), retries on 429, 5xx, NETWORK_ERROR, TIMEOUT
- **Context overflow**: ContextManager compaction triggered + replay
- **Streaming**: Heuristic token estimate when API returns usage: null
- **Truncated tool calls**: When finishReason=length produces invalid JSON, asks LLM to retry with smaller operation
- **Model fallback**: Opt-in (`AgentSettings.enableModelFallback`). `ModelFallbackManager` advances through fallback chain (Opus thinking → Opus → Sonnet thinking → Sonnet, no Haiku). After 3 successful iterations on fallback, attempts escalation. If escalation fails, waits 6 iterations.

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

## Three-Tier Memory System

### Tier 1: Core Memory (always in prompt, 4KB)
- Location: `~/.workflow-orchestrator/{proj}/agent/core-memory.json`
- Fixed-size key-value store, injected as `<core_memory>` in system prompt
- Self-editable by agent via `core_memory_read`, `core_memory_append`, `core_memory_replace` tools
- Loaded at session start by `CoreMemory.forProject()`

### Tier 2: Archival Memory (searchable, unlimited)
- Location: `~/.workflow-orchestrator/{proj}/agent/archival/store.json`
- JSON-backed store with LLM-generated tags for keyword search
- Insert via `archival_memory_insert`, search via `archival_memory_search`
- Tag-boosted keyword matching (3x tag boost, sub-millisecond for <5K entries)
- Cap: 5000 entries, oldest evicted when full

### Tier 3: Conversation Recall (past session search)
- `conversation_search` tool for keyword search across past session transcripts
- Read-only — sessions persisted by MessageStateHandler

### Legacy: Markdown Memory
- Location: `~/.workflow-orchestrator/{proj}/agent/memory/`
- `save_memory` tool, loaded via `AgentMemoryStore.loadMemories()`

### Memory Tools (7 total)
| Tool | Tier | Description |
|------|------|-------------|
| `core_memory_read` | Core | Read current core memory block |
| `core_memory_append` | Core | Add/update entry in core memory |
| `core_memory_replace` | Core | Replace or delete core memory entry |
| `archival_memory_insert` | Archival | Store long-term knowledge with tags |
| `archival_memory_search` | Archival | Keyword search over archival store |
| `conversation_search` | Recall | Search past session transcripts |
| `save_memory` | Legacy | Save markdown memory file |

### Auto-Memory System (Retrieval-Only)

One automatic trigger — session-start retrieval (keyword extraction + archival search + staleness filter, zero LLM cost). No automatic session-end extraction.

**Key files:**
- `memory/auto/AutoMemoryManager.kt` — retrieval-only wrapper around `RelevanceRetriever`
- `memory/auto/RelevanceRetriever.kt` — keyword-based archival search with staleness filter (suppresses entries mentioning missing file paths)

Gated by `AgentSettings.state.autoMemoryEnabled` (default true).

### Memory Management UI

**Settings page:** Tools → Workflow Orchestrator → AI Agent → Memory
- Toggle retrieval on/off, view/edit core memory blocks, clear memory with confirmation

**TopBar indicator:** Badge showing `◆ {coreKB} | {archivalCount}`. Click opens Settings.

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
- Built-in skills: `systematic-debugging`, `interactive-debugging`, `create-skill`, `git-workflow`, `brainstorm`, `writing-plans`, `tdd`, `subagent-driven`

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

The `agent` tool spawns, resumes, and manages subagent workers:

**Spawn:** `agent(description="...", prompt="...", subagent_type="coder")`
**Background:** `agent(description="...", prompt="...", run_in_background=true)` — returns immediately with agentId
**Resume:** `agent(resume="agentId", prompt="continue with authorization module")` — continues with full previous context
**Kill:** `agent(kill="agentId")` — cancels a running background agent
**Send:** `agent(send="agentId", message="focus on service layer")` — sends instruction to running worker

**Built-in types:** general-purpose, explorer (PSI-powered, read-only, thoroughness: quick/medium/very thorough), coder, reviewer, tooler
**Bundled specialist agents** (from `agent/src/main/resources/agents/`): code-reviewer, architect-reviewer, test-automator, spring-boot-engineer, refactoring-specialist, devops-engineer, security-auditor, performance-engineer — overridable by user/project agents
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
| `memory` | none | Persistent memory: user/project/local |

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

**Resource serving:** `CefResourceSchemeHandler` serves from plugin JAR via `http://workflow-agent/` scheme. CSP: `connect-src: 'none'`.

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

## Streaming Text Pipeline

Two layers between raw SSE token and rendered DOM:

1. **StreamBatcher** (Kotlin, `agent/ui/StreamBatcher.kt`): 16ms EDT timer coalesces rapid chunks into single bridge calls (~5000 → ~300 per response).
2. **chatStore streaming-message model**: `appendToken()` creates/updates a placeholder `Message` in-place. `endStream()` just clears `activeStream`. Same `AgentMessage` component renders both streaming and finalized — no mount/unmount flash.

**Incomplete code fences** render as plain `<pre class="streaming-code-plain">` until closed, then swap to Shiki-backed `CodeBlock`.
**Module-scope invariant:** `MarkdownRenderer.tsx` declares `COMPONENTS`, `REMARK_PLUGINS`, `REHYPE_PLUGINS` at module scope (inline literals defeat Streamdown's per-block `React.memo`).

## Testing

```bash
./gradlew :agent:test                    # All agent tests (~112 test files)
./gradlew :agent:test --tests "...Test"  # Specific test class
./gradlew :agent:clean :agent:test --rerun --no-build-cache  # Clean rebuild
```

Key test patterns: JUnit 5 + MockK + `@TempDir` for file I/O, `runTest` for coroutines, `mockk<Project>` for IntelliJ services.

## IntelliJ-Native APIs

Core tools (read, edit, search) use IntelliJ Document API and VFS for undo support, unsaved change visibility, and editor sync.

# Workflow Orchestrator — IntelliJ Plugin

Plugin ID: `com.workflow.orchestrator.plugin` | Kotlin 2.1.10 | Gradle + IntelliJ Platform Plugin v2 | Target: IntelliJ IDEA 2025.1+

## Build & Verify

```bash
./gradlew :core:test                # Core unit tests
./gradlew :jira:test                # Jira module tests
./gradlew :bamboo:test              # Bamboo module tests
./gradlew :sonar:test               # Sonar module tests
./gradlew :pullrequest:test         # PR module tests
./gradlew :automation:test          # Automation module tests
./gradlew :handover:test            # Handover module tests
./gradlew verifyPlugin              # API compatibility check
./gradlew buildPlugin               # Build installable ZIP
./gradlew runIde                    # Launch sandbox IDE with plugin
```

## Architecture

9 Gradle submodules (7 feature modules + core + mock-server):

| Module | Purpose |
|---|---|
| `:core` | Auth, HTTP, settings, events, polling, health checks, tool window shell, PSI context enrichment, AI commit message generation |
| `:jira` | Sprint dashboard, branching, commit prefix, time tracking, ticket detection |
| `:bamboo` | Build dashboard, polling, log parsing, CVE remediation, PR creation |
| `:sonar` | Coverage markers, quality tab, ExternalAnnotator, project key detection, Sonar fix intention action |
| `:pullrequest` | PR list/detail dashboard, merge actions, Bitbucket PR management |
| `:automation` | Docker tag staging, queue management, drift/conflict detection |
| `:handover` | Jira closure, copyright fixes, AI pre-review, QA clipboard, time logging |
| `:agent` | AI coding agent faithfully ported from Cline (VS Code) — ReAct loop (AgentLoop), thread-safe ToolRegistry (ConcurrentHashMap, synchronized deferred activation) with per-tool timeouts (default 120s, RunCommandTool 600s, SpawnAgentTool unlimited), 3-tier deferred loading (~30 core + ~50 deferred via `tool_search`, conditional integration tools via `reregisterConditionalTools()`, IDE context detection (IdeContextDetector — product/edition/plugins/frameworks, ToolRegistrationFilter guards Java/Spring/build/debug tools)), Cline-ported 11-section system prompt (SystemPrompt, IDE-aware via IdeContext — agentRole/capabilities/rules/systemInfo adapt to IDE product), 3-stage ContextManager (file read dedup + conversation truncation + LLM summarization), loop detection (3 soft/5 hard), 8 lifecycle hooks (HookManager, PreToolUse enriched with riskLevel + isWriteTool), explicit completion via `attempt_completion`, plan mode with `plan_mode_respond` (user-controlled act switch), skill system (`use_skill` + InstructionLoader with SKILL.java.md/SKILL.python.md language variants), session handoff (`new_task`), two-file JSON session persistence with streaming crash safety (MessageStateHandler), typed task system (task_create/task_update/task_list/task_get with TaskStore, blocks/blockedBy DAG, hook-exempt), cost tracking, diff view, sub-agent delegation (`agent` tool, 3 scopes: research/implement/review, parallel research up to 5 concurrent, configurable context budget), 8 bundled specialist agent personas (IDE-filtered via AgentConfigLoader.filterByIdeContext) + user-defined YAML agents via AgentConfigLoader, per-tool approval policies (ApprovalPolicy — `run_command` always per-invocation, session-scoped approval store at AgentController level), tool output management (per-tool size limits, `grep_pattern` for LLM-side filtering, `output_file` for disk spill, ToolOutputSpiller auto-spills >30K outputs), full-fidelity tool output (every runtime/debug/inspection/DB/PSI tool >30K is disk-spilled via ToolOutputSpiller; preview-with-path returned to LLM, full content readable via read_file/search_code), `java_runtime_exec.run_tests` pre-flight validation via `BuildSystemValidator` (catches missing settings.gradle entries, main-sources classes, zero @Test methods before dispatch — see `docs/plans/2026-04-17-phase2-multi-module-build-validation.md`), JCEF chat UI |

**Dependency rule:** Feature modules depend ONLY on `:core`. Cross-module communication uses `EventBus` (`SharedFlow<WorkflowEvent>` in `:core`).

**Module layering:** `api/` (HTTP client + DTOs) -> `service/` (business logic, suspend funs) -> `ui/` (panels, actions, gutter icons) -> `listeners/` (IDE event listeners)

## Unified Service Layer

Service interfaces in `:core`: `JiraService`, `BambooService`, `SonarService`, `BitbucketService`.
Implementations in feature modules (e.g., `JiraServiceImpl`, `BambooServiceImpl`).
All return `ToolResult<T>` with typed `.data` for UI and `.summary` for logging/notifications.

## Agent-Exposable Service Architecture (IMPORTANT)

The `:agent` module (on a separate branch) exposes workflow capabilities as AI agent tools. For any new service method to be usable by the AI agent, it MUST follow this architecture:

### The Pattern

```
1. core/services/XxxService.kt    → Add method to interface, return ToolResult<T>
2. module/service/XxxServiceImpl.kt → Implement the method
3. agent/tools/integration/XxxTool.kt → Wrap as agent tool (done on agent branch)
```

### Rules for New Service Methods

**ALWAYS do this when adding a new feature that involves API calls or business logic:**

1. **Add the method signature to the core service interface** (`core/services/JiraService.kt`, `BambooService.kt`, `SonarService.kt`, `BitbucketService.kt`)
2. **Return `ToolResult<T>`** — not `ApiResult`, not raw DTOs, not `Unit`
   - `ToolResult<T>` has `.data` (typed result for UI) and `.summary` (human-readable string for logging/AI)
3. **Implement in the feature module** (`jira/service/JiraServiceImpl.kt`, etc.)
4. **Do NOT put business logic only in UI panels or raw API clients** — if a panel can do it, a service method should exist for it

### Why This Matters

The AI agent (`:agent` module) depends ONLY on `:core`. It accesses services via the core interfaces. If a capability exists only in:
- `JiraApiClient` (raw HTTP) → agent can't use it (wrong module, wrong return type)
- `PrActionService` (`:pullrequest` module) → agent can't use it (wrong dependency)
- A UI panel method → agent definitely can't use it

Only methods on the **core service interfaces** with **`ToolResult<T>` return type** are accessible to the agent.

### Example — Correct vs Incorrect

**CORRECT (agent can use this):**
```kotlin
// core/services/BambooService.kt
interface BambooService {
    suspend fun stopBuild(resultKey: String): ToolResult<Unit>  // ✓ In core, returns ToolResult
}

// bamboo/service/BambooServiceImpl.kt
override suspend fun stopBuild(resultKey: String): ToolResult<Unit> {
    // implementation
}
```

**INCORRECT (agent CANNOT use this):**
```kotlin
// jira/api/JiraApiClient.kt
suspend fun getWorklogs(issueKey: String): ApiResult<JiraWorklogResponse> {  // ✗ In module, returns ApiResult
    // Only accessible to jira UI, not to agent
}
```

### Checklist for New Features

When adding a new capability (API endpoint, action, query):
- [ ] Is the method on the core service interface? (`core/services/Xxx.kt`)
- [ ] Does it return `ToolResult<T>`?
- [ ] Is the `T` data class defined in `core/model/`?
- [ ] Does the `ToolResult` include a meaningful `.summary` string?
- [ ] Is the implementation in the feature module's service class?

If any answer is NO, the AI agent cannot use this feature.

## Shared Utilities (in :core)

- **StatusColors** — JBColor constants: SUCCESS, ERROR, WARNING, INFO, LINK, OPEN, MERGED, DECLINED, SECONDARY_TEXT
- **TimeFormatter** — relative/absolute time formatting plus duration helpers
  (`formatDurationSeconds`, `formatDurationMillis`, `formatEffortMinutes`)
- **HttpClientFactory** — shared ConnectionPool, per-service auth scheme (Bearer/Basic/Token).
  `HttpClientFactory.timeoutsFromSettings(project)` returns the configured `HttpTimeouts` pair so
  individual API client constructions don't repeat the `httpConnectTimeoutSeconds.toLong()` boilerplate.
- **BitbucketBranchClient.fromConfiguredSettings()** — factory that builds a `BitbucketBranchClient`
  from the application-level Bitbucket URL + credential, returning `null` when not configured.
  All cached/ad-hoc clients in pullrequest/bamboo/jira/sonar go through this.
- **RepoContextResolver.resolveCurrentEditorRepoOrPrimary()** / **resolvePrimaryGitRepo()** —
  centralised "editor repo or primary, materialised as `GitRepository`" lookup that was duplicated
  across bamboo / sonar / pullrequest / jira / core.
- **ClipboardUtil** — `copyToClipboard(text)` wrapper around the AWT system clipboard.
- **HtmlEscape** — `escapeHtml(s)` for safe embedding of user text into HTML body / attributes.
- **StringUtils** — `truncate(text, maxLength)` ellipsis-truncation helper.
- **SmartPoller** — activity-aware polling with exponential backoff (1.5x) + jitter, visibility gating
- **EventBus** — SharedFlow for cross-module events (see `WorkflowEvent.kt` for full list)
- **CredentialStore** — PasswordSafe wrapper for all secrets

## Plan Mode (from Cline)

Explore -> plan -> revise -> act flow ported from Cline. Two enforcement layers:

1. **Schema filtering** (AgentService): write tools (`edit_file`, `create_file`, `run_command`, `revert_file`,
   `kill_process`, `send_stdin`, `format_code`, `optimize_imports`, `refactor_rename`)
   and `enable_plan_mode` removed from tool definitions in plan mode; `plan_mode_respond` removed in act mode
2. **Execution guard** (AgentLoop): `WRITE_TOOLS` set blocked even if LLM hallucinates them past schema filtering

- **Activation:** UI Plan button toggle OR LLM calls `enable_plan_mode` tool
- **Deactivation:** User approves plan (switches to act), user unclicks Plan button, new chat, or cancel
- **State:** `AgentService.planModeActive` (AtomicBoolean) — single source of truth
- **Plan flow:** LLM uses read/search tools to explore, calls `plan_mode_respond` with plan.
  If `needsMoreExploration=true`, loop continues immediately. If `false`, loop suspends via `userInputChannel`
  waiting for user input (chat message, plan comments, or approve). User types freely to discuss/refine.
- **Act switch:** Only the user can switch to act mode (click Approve). LLM CANNOT switch to act mode.
- **Act flow:** After approval, `planModeActive` set to false, write tools re-enabled. LLM proceeds
  with task execution using the full tool set.

## Threading

- API calls: `suspend fun` on `Dispatchers.IO`
- UI updates: `invokeLater` or `withContext(Dispatchers.EDT)`
- File writes: `WriteCommandAction.runWriteCommandAction()`
- User operations: `runBackgroundableTask`
- Background polling: `CoroutineScope` + `SupervisorJob` tied to `Disposable`
- Sub-agents: `SubagentRunner` wraps `AgentLoop` per sub-agent with progress/stats tracking
- Parallel sub-agents: `supervisorScope` + `async` for research scope (up to 5 concurrent)
- Tool execution: `withTimeoutOrNull` per tool call (default 120s, `run_command` 600s, `agent` unlimited); `CancellationException` re-thrown (not swallowed)
- Use coroutine scope, not `runBlocking`, in Swing callbacks

## Authentication

| Service | Auth Scheme | Header |
|---|---|---|
| Jira, Bamboo, Bitbucket | Bearer PAT | `Authorization: Bearer <token>` |
| SonarQube | Bearer user token | `Authorization: Bearer <token>` |
| Nexus Docker Registry | Basic auth | `Authorization: Basic <base64>` |
| Sourcegraph | Token auth | `Authorization: token <sourcegraph-access-token>` |

All credentials stored in PasswordSafe. Never in XML.

## Agent Storage

All agent data lives under `~/.workflow-orchestrator/{ProjectName-hash}/` (computed by `ProjectIdentifier`):

| Data | Path | Retention |
|---|---|---|
| Global session index | `agent/sessions.json` | Persistent |
| API conversation history | `agent/sessions/{sessionId}/api_conversation_history.json` | Per-session |
| UI messages | `agent/sessions/{sessionId}/ui_messages.json` | Per-session |
| Session lock | `agent/sessions/{sessionId}/.lock` | Per-session |
| Checkpoints | `agent/sessions/{sessionId}/checkpoints/` | Per-session |
| Core memory | `agent/core-memory.json` | Persistent |
| Archival memory | `agent/archival/store.json` | Persistent (5000 cap) |
| Legacy memory | `agent/memory/` | Persistent |
| Logs | `logs/agent-YYYY-MM-DD.jsonl` | 7 days |

Project identifier format: `{dirName}-{first6OfSHA256(absolutePath)}`.

## Agent Personas & Dynamic Agent Configs

Loaded by `AgentConfigLoader` (singleton, Disposable lifecycle) from two sources:

1. **Bundled** (`agent/src/main/resources/agents/`): 8 specialist agents shipped with the plugin —
   code-reviewer, architect-reviewer, test-automator, spring-boot-engineer, refactoring-specialist,
   devops-engineer, security-auditor, performance-engineer
2. **User-defined** (`~/.workflow-orchestrator/agents/`): custom YAML/MD configs, file-watched with
   300ms debounce hot-reload

Each agent config is a markdown file with YAML frontmatter (name, description, tools, skills, modelId,
max-turns) and a system prompt body. Each registers as a `use_subagent_{name}` tool callable by the LLM
(tool names generated by `SubagentToolName` with FNV-1a hash for collision avoidance).

User configs override bundled agents with the same name (case-insensitive).

The `agent` tool (`SpawnAgentTool`) delegates to `SubagentRunner` which wraps `AgentLoop` with per-subagent
stats tracking (`SubagentRunStats`), progress streaming via callbacks, and cancellation. Research scope
supports up to 5 parallel prompts via `supervisorScope` + `async`. Context budget is configurable
(default 150K tokens, was hardcoded 50K).

Key files: `tools/subagent/SubagentModels.kt` (data classes), `tools/subagent/SubagentToolName.kt`
(name generation), `tools/subagent/AgentConfigLoader.kt` (YAML loading + file watching),
`tools/subagent/SubagentRunner.kt` (execution wrapper), `tools/builtin/SpawnAgentTool.kt` (orchestration).

**Persistence pattern** (ported from Cline's `message-state.ts` + `disk.ts`): Two-file JSON persistence
(api_conversation_history.json + ui_messages.json) with per-change atomic rewrite under coroutine Mutex.
Named checkpoints created after write operations (`edit_file`, `create_file`, etc.) for checkpoint
reversion support. Atomic writes (write to .tmp then `Files.move(ATOMIC_MOVE)`) for crash safety.
Streaming chunks persisted with `partial: true` flag, flipped on stream end.

**Hook config:** `.agent-hooks.json` in project root (8 hook types: TaskStart, UserPromptSubmit,
TaskResume, PreCompact, TaskCancel, PreToolUse, PostToolUse, TaskComplete).
See `docs/architecture/agent-architecture.md` Section 11.

**Skills:** Bundled from classpath `/skills/`, project-local from `{projectPath}/.agent-skills/`,
global from `~/.workflow-orchestrator/skills/`. Each skill is a directory with `SKILL.md` (YAML frontmatter).

## Agent Memory System

Three-tier storage (Letta/MemGPT pattern) with event-driven triggers ported from best-practice research:

**Storage (existing):**
- **Tier 1 — Core Memory** (`core-memory.json`): Named blocks always injected as `<core_memory>` in system prompt.
- **Tier 2 — Archival Memory** (`archival/store.json`): JSON store with tag-boosted keyword search.
- **Tier 3 — Conversation Recall**: Keyword search across past session conversation history (api_conversation_history.json).

**Triggers (event-driven, system-managed via `AutoMemoryManager`):**
- **Session-end extraction**: After completed sessions, cheap LLM (Haiku) extracts insights → core + archival.
- **Session-start retrieval**: Keywords from first message → archival search → `<recalled_memory>` in prompt.

**User control:** Settings page (Tools → Workflow Orchestrator → AI Agent → Memory) for view/edit/clear. TopBar indicator shows memory usage with click-through to settings. `autoMemoryEnabled` toggle (default on) in settings.

LLM memory tools (`core_memory_read/append/replace`, `archival_memory_insert/search`, `conversation_search`) remain available as manual overrides. Key class: `AutoMemoryManager` in `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/auto/`.

## UX Constraints

- ONE tool window "Workflow" (bottom-docked), 6 tabs: Sprint, PR, Build, Quality, Automation, Handover
- Session history is integrated into the Agent chat webview as a `HistoryView` React component (not a separate tab).
  Webview toggles between `viewMode: 'history'` (session cards, search, empty state) and `viewMode: 'chat'` (active session).
  Bridge functions: `_loadSessionHistory` (K→JS), `_showSession`/`_deleteSession`/`_toggleFavorite`/`_startNewSession` (JS→K).
- ONE status bar widget per service area
- JB components only: JBList, JBTable, JBSplitter, JBColor, JBUI.Borders
- SVG icons with light + dark variants; reuse `AllIcons.*` for standard concepts
- Empty states: "No [entity] [state]." + action link
- Settings: 4 pages under Tools > Workflow Orchestrator: General, Workflow, CI/CD, AI & Advanced
- Onboarding: GotItTooltip on first encounter, collapsible setup dialog (no modal at startup)

## Documentation Maintenance

When making changes that affect architecture, modules, events, services, APIs, or UI structure:
- Update the relevant module-level `CLAUDE.md` file
- Update `docs/architecture/` diagrams and docs (including `index.html`)
- Update this root `CLAUDE.md` if it affects build commands, module list, threading rules, or UX constraints

Do this immediately as part of the same commit — never defer documentation updates.

## Release Process

When asked to release:
1. Bump `pluginVersion` in `gradle.properties`
2. Run `./gradlew clean buildPlugin`
3. Push commits to GitHub
4. Create a GitHub release with `gh release create` attaching the built ZIP from `build/distributions/`

## Rebase Process

Before rebasing with main:
1. `git fetch origin main` — check what's new
2. `git log --oneline --name-only HEAD..origin/main` — analyze new commits and changed files
3. Check if new services, tools, events, or APIs were added on main that need integration with `:agent`
4. Check for changes in `:core` service interfaces (SonarService, JiraService, etc.) that agent tools depend on
5. Only then: `git rebase origin/main`
6. After rebase: `./gradlew :agent:clean :agent:test --rerun --no-build-cache` + `./gradlew verifyPlugin`
7. If new capabilities were added on main, create corresponding agent tools

## Key References

- Architecture diagrams: `docs/architecture/` (open `index.html` in browser for interactive view)
- Design spec: `docs/superpowers/specs/2026-03-11-workflow-orchestrator-plugin-design.md`
- UX guide: `ux-design-guide.md`
- Requirements: `requirement.md`
- Features: `features.md`
- Workflow: `workflow.md`
- API details: see module-level CLAUDE.md files

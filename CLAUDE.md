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
| `:agent` | AI coding agent faithfully ported from Cline (VS Code) — ReAct loop (AgentLoop), ~70 registered tools in 3-tier ToolRegistry (core/deferred/active via tool_search), Cline-ported 11-section system prompt (SystemPrompt), 3-stage ContextManager (file read dedup + conversation truncation + LLM summarization), loop detection (3 soft/5 hard), 7 lifecycle hooks (HookManager), explicit completion via `attempt_completion`, plan mode with `plan_mode_respond`/`act_mode_respond`, skill system (`use_skill`), session handoff (`new_task`), JSONL session persistence with checkpoint reversion (SessionStore), task progress (FocusChain), cost tracking, diff view, sub-agent delegation (`spawn_agent`) with parallel research subagents (up to 5 concurrent), dynamic agent configs via YAML (`AgentConfigLoader`), configurable context budget, JCEF chat UI |

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
- **TimeFormatter** — relative/absolute time formatting
- **HttpClientFactory** — shared ConnectionPool, per-service auth scheme (Bearer/Basic/Token)
- **SmartPoller** — activity-aware polling with exponential backoff (1.5x) + jitter, visibility gating
- **EventBus** — SharedFlow for cross-module events (see `WorkflowEvent.kt` for full list)
- **CredentialStore** — PasswordSafe wrapper for all secrets

## Plan Mode (from Cline)

Explore -> plan -> revise -> act flow ported from Cline. Two enforcement layers:

1. **Schema filtering** (AgentService): write tools (`edit_file`, `create_file`, `run_command`, `revert_file`,
   `kill_process`, `send_stdin`, `format_code`, `optimize_imports`, `refactor_rename`) + `act_mode_respond`
   removed from tool definitions in plan mode; `plan_mode_respond` removed in act mode
2. **Execution guard** (AgentLoop): `WRITE_TOOLS` set blocked even if LLM hallucinates them past schema filtering

- **Activation:** UI Plan button toggle
- **Deactivation:** User approves plan (switches to act), user unclicks Plan button, new chat, or cancel
- **State:** `AgentService.planModeActive` (AtomicBoolean) — single source of truth
- **Plan flow:** LLM uses read/search tools to explore, calls `plan_mode_respond` with plan.
  If `needsMoreExploration=true`, loop continues. If `false`, returns `LoopResult.PlanPresented` for user review.
- **Act flow:** After plan approval, loop switches to act mode. LLM uses `act_mode_respond` for progress
  updates (cannot be called consecutively — ported from Cline)

## Threading

- API calls: `suspend fun` on `Dispatchers.IO`
- UI updates: `invokeLater` or `withContext(Dispatchers.EDT)`
- File writes: `WriteCommandAction.runWriteCommandAction()`
- User operations: `runBackgroundableTask`
- Background polling: `CoroutineScope` + `SupervisorJob` tied to `Disposable`
- Parallel subagents: `supervisorScope` + `async` for research scope (up to 5 concurrent)
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
| Session metadata | `agent/sessions/{sessionId}.json` | Per-session |
| Conversation history | `agent/sessions/{sessionId}/messages.jsonl` | Per-session |
| Checkpoints | `agent/sessions/{sessionId}/checkpoints/{cpId}.jsonl` | Per-session |
| Checkpoint metadata | `agent/sessions/{sessionId}/checkpoints/{cpId}.meta.json` | Per-session |
| Logs | `logs/agent-YYYY-MM-DD.jsonl` | 7 days |

Project identifier format: `{dirName}-{first6OfSHA256(absolutePath)}`.

## Dynamic Agent Configs

YAML config files in `~/.workflow-orchestrator/agents/` define custom subagent personas with hot-reload.
Each `.yaml` file has YAML frontmatter (name, description, tools, skills, modelId) and a markdown body
that becomes the system prompt. Loaded by `AgentConfigLoader` on startup, file-watched for changes.

Example:
```yaml
---
name: "Code Reviewer"
description: "Reviews code for bugs and quality"
tools: "read_file, search_code, find_references"
modelId: "optional/model-override"
---
You are a code review specialist. Analyze code for...
```

Registered as dynamic tools (`use_subagent_code_reviewer`) callable by the LLM.

**Persistence pattern** (ported from Cline's `message-state.ts`): JSONL append after every tool execution
for conversation history. Session metadata updated atomically after each checkpoint. Named checkpoints
created after write operations (`edit_file`, `create_file`, etc.) for checkpoint reversion support.

**Hook config:** `.agent-hooks.json` in project root (7 hook types: TaskStart, UserPromptSubmit,
TaskResume, PreCompact, TaskCancel, PreToolUse, PostToolUse). See `docs/architecture/agent-architecture.md` Section 11.

**Skills:** Bundled from classpath `/skills/`, project-local from `{projectPath}/.agent-skills/`,
global from `~/.workflow-orchestrator/skills/`. Each skill is a directory with `SKILL.md` (YAML frontmatter).

## UX Constraints

- ONE tool window "Workflow" (bottom-docked), 6 tabs: Sprint, PR, Build, Quality, Automation, Handover
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

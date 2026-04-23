# Workflow Orchestrator — IntelliJ Plugin

Plugin ID: `com.workflow.orchestrator.plugin` | Kotlin 2.1.10 | Gradle + IntelliJ Platform Plugin v2 | Target: IntelliJ IDEA 2025.1+

## Build

```bash
./gradlew :<module>:test   # core/jira/bamboo/sonar/pullrequest/automation/handover
./gradlew verifyPlugin buildPlugin runIde
```

## Modules

9 submodules — feature modules depend ONLY on `:core`; cross-module via `EventBus` (`SharedFlow<WorkflowEvent>`). Layering: `api/` → `service/` → `ui/` → `listeners/`

| Module | Purpose |
|---|---|
| `:core` | Auth, HTTP, settings, events, polling, tool window, PSI context, AI commits |
| `:jira` | Sprint, branching, commit prefix, time tracking |
| `:bamboo` | Build dashboard, log parsing, CVE remediation |
| `:sonar` | Coverage markers, ExternalAnnotator, fix intention |
| `:pullrequest` | PR dashboard, merge, Bitbucket |
| `:automation` | Docker tags, queue, drift detection |
| `:handover` | Jira closure, copyright, AI pre-review, QA clipboard |
| `:agent` | Cline-ported ReAct loop; ToolRegistry (deferred, per-tool timeouts); 11-section IDE-aware system prompt; 3-stage ContextManager; plan mode; skill system; two-file atomic JSON persistence + checkpoints; typed tasks (DAG); sub-agents (3 scopes, 5 parallel); 8 bundled personas + user YAML; ToolOutputSpiller (>30K); BuildSystemValidator; JCEF chat UI |

## Service Architecture (IMPORTANT)

`:agent` depends ONLY on `:core`. New features must follow: **core interface → `ToolResult<T>` → feature impl → agent tool wrapper**. Methods on module clients or UI panels are inaccessible to the agent.

Checklist: method on `core/services/Xxx.kt`? Returns `ToolResult<T>`? `T` in `core/model/`? Meaningful `.summary`? Impl in feature module?

## Core Utilities

`StatusColors`, `TimeFormatter`, `HttpClientFactory.timeoutsFromSettings()`, `BitbucketBranchClient.fromConfiguredSettings()`, `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()`, `ClipboardUtil`, `HtmlEscape`, `StringUtils.truncate`, `SmartPoller` (1.5x backoff), `EventBus`, `CredentialStore`

## Threading

`Dispatchers.IO` for API | `invokeLater`/`Dispatchers.EDT` for UI | `WriteCommandAction` for files | `CoroutineScope+SupervisorJob` for polling | `withTimeoutOrNull` per tool (120s/600s run_command/unlimited agent) | Never `runBlocking` in Swing

## Auth

Jira/Bamboo/Bitbucket/Sonar: `Authorization: Bearer <token>` | Nexus: `Authorization: Basic <base64>` | Sourcegraph: `Authorization: token <token>` — all in PasswordSafe, never XML.

## Agent Storage

Base: `~/.workflow-orchestrator/{dirName}-{first6OfSHA256}/agent/`
- `sessions.json` — global index
- `sessions/{id}/api_conversation_history.json` + `ui_messages.json` + `checkpoints/`
- `memory/MEMORY.md` (index, always injected when present) + `memory/<type>_<topic>.md` files | `logs/` (7 days)

## Agent Configs & Persistence

8 bundled personas + user YAML from `~/.workflow-orchestrator/agents/` (300ms hot-reload). Atomic two-file JSON persistence (`.tmp` + `Files.move(ATOMIC_MOVE)`), coroutine Mutex, checkpoints after write ops. Hooks: `.agent-hooks.json` (TaskStart/UserPromptSubmit/TaskResume/PreCompact/TaskCancel/PreToolUse/PostToolUse/TaskComplete). Skills: classpath `/skills/`, `.agent-skills/`, `~/.workflow-orchestrator/skills/`.

## Plan Mode

Schema filtering removes write tools in plan mode; execution guard in AgentLoop blocks `WRITE_TOOLS`. State: `AgentService.planModeActive` (AtomicBoolean). LLM uses `plan_mode_respond`; loop suspends when `needsMoreExploration=false`. Only user can switch to act (Approve button).

## Agent Memory

Per-project file-based memory at `~/.workflow-orchestrator/{proj}/agent/memory/`. `MEMORY.md` (≤200 lines) is always injected into the system prompt. Individual memory files (`<type>_<topic>.md` with YAML frontmatter: `name`, `description`, `type ∈ {user, feedback, project, reference}`) are loaded on demand via `read_file`. No specialized memory tools — the LLM uses `read_file` / `create_file` / `edit_file` directly. See `agent/CLAUDE.md` → "File-Based Memory System".

## UX

- Tool window "Workflow" (bottom-docked), 6 tabs: Sprint, PR, Build, Quality, Automation, Handover
- Agent chat: `HistoryView` React in webview; `viewMode: 'history'/'chat'`; bridge: `_loadSessionHistory`/`_showSession`/`_deleteSession`/`_toggleFavorite`/`_startNewSession`
- JB components only; SVG icons light+dark; empty states: "No [entity] [state]." + action link
- Settings: 4 pages under Tools > Workflow Orchestrator | Onboarding: GotItTooltip, no startup modal

## Release

1. Bump `pluginVersion` in `gradle.properties`
2. `./gradlew clean buildPlugin`
3. Push + `gh release create` with ZIP from `build/distributions/`

## Rebase

1. `git fetch origin main` + analyze new commits for `:agent` integration needs
2. `git rebase origin/main`
3. `./gradlew :agent:clean :agent:test --rerun --no-build-cache` + `./gradlew verifyPlugin`

## Docs

Update module `CLAUDE.md` + `docs/architecture/` (incl. `index.html`) in same commit as architecture changes. Refs: `docs/architecture/index.html` | `ux-design-guide.md` | `requirement.md` | `features.md`

# :agent Module

AI coding agent with ReAct loop, LLM-controlled delegation, interactive planning, and 98 tools.

## LLM API

Uses Sourcegraph Enterprise's OpenAI-compatible API:
- Endpoint: `/.api/llm/chat/completions`
- Auth: `token` scheme via `Authorization: token <sourcegraph-access-token>`
- Constraints: 190K input tokens (configurable), no `system` role (converted to user with `<system_instructions>` tags), no `tool_choice`, strict user/assistant alternation
- Output limit varies per model — no hardcoded clamp. User configures maxOutputTokens in settings.
- Message sanitization in `SourcegraphChatClient.sanitizeMessages()`: system→user, tool→user with `<tool_result>` tags, consecutive same-role merging

## Architecture

```
AgentController (UI entry point)
  → ConversationSession (long-lived, owns context + plan + question managers)
    → AgentOrchestrator.executeTask()
      → SingleAgentSession.execute() (ReAct loop, max 50 iterations)
        → BudgetEnforcer (COMPRESS at 80%, NUDGE at 88%, STRONG_NUDGE at 93%, TERMINATE at 97%)
        → LoopGuard (loop detection, error nudges, auto-verification)
        → Tool execution with optional ApprovalGate
```

## Key Components

- **SingleAgentSession** — Core ReAct loop. Budget enforcement, nudge injection, tool call processing, context reduction on API errors. Calls `compressWithLlm(brain)` for LLM-powered compression. Truncated tool call recovery — detects invalid JSON when finishReason=length, asks LLM to retry with smaller operation. Context budget awareness (system_warning at >50% fill). Graceful degradation (80% iterations = wrap-up nudge, 95% = force text-only). Mid-loop cancellation support. Parallel read-only tool execution (via coroutineScope+async). Context overflow replay (compress + retry same request). Doom loop detection before each tool call. Streaming token estimate when usage is null.
- **ConversationSession** — Long-lived session across user messages. Owns `ContextManager`, `PlanManager`, `QuestionManager`, `WorkingSet`, `RollbackManager`. Persisted to JSONL.
- **ContextManager** — Two-threshold compression (T_max=93%, T_retained=70%). Two-phase compression: Phase 1 prunes old tool results (protects last 30K tokens), Phase 2 is LLM/truncation summarization. `compressWithLlm()` uses structured compaction template (Goal/Instructions/Discoveries/Accomplished/Relevant Files). Anchored summaries capped at 3 (consolidated when exceeded). Dedicated `planAnchor` slot survives compression. Token reconciliation with API's actual `prompt_tokens` after each LLM call. Old system messages (LoopGuard reminders, budget warnings) are compressible — only the original system prompt is protected. Not thread-safe — must be accessed from a single coroutine context.
- **BudgetEnforcer** — Four-status budget monitoring: OK (<80%), COMPRESS (80-88%), NUDGE (88-93%), STRONG_NUDGE (93-97%), TERMINATE (>97%).
- **GuardrailStore** — Persistent learned constraints (`.workflow/agent/guardrails.md`). Auto-recorded from doom loops/circuit breakers, loaded into system prompt and compression-proof anchor.
- **BackpressureGate** — Edit-count tracker that injects verification nudges after N edits without running diagnostics/tests.
- **RotationState** — Serializable context handoff state for graceful session rotation when budget is exhausted.
- **SpawnAgentTool** (`agent`) — Primary tool for spawning subagents, matching Claude Code's Agent tool design. Only `description` and `prompt` required. `subagent_type` selects built-in (general-purpose/explorer/coder/reviewer/tooler) or custom agents from `.workflow/agents/`. Defaults to general-purpose. Explorer type uses PSI-first search strategy with thoroughness calibration (quick/medium/very thorough) and is restricted to read-only tools only (no debug, config, or edit tools).
- **DelegateTaskTool** (`delegate_task`) — [DEPRECATED] Legacy worker spawning tool. Use `agent` tool instead. Kept for backward compatibility.
- **WorkerSession** — Scoped ReAct loop (max 10 iterations) with parent Job cancellation support.

## Tools (98 total, 10 categories)

| Category | Tools |
|----------|-------|
| Core (always active) | read_file, edit_file, search_code, run_command, glob_files, diagnostics, problem_view, format_code, optimize_imports, file_structure, find_definition, find_references, type_hierarchy, call_hierarchy, get_annotations, get_method_body, agent, delegate_task (deprecated), think |
| IDE Intelligence | run_inspections, refactor_rename, list_quickfixes, compile_module, run_tests, find_implementations |
| Runtime & Debug | get_run_configurations, get_running_processes, get_run_output, get_test_results, add_breakpoint, remove_breakpoint, list_breakpoints, start_debug_session, get_debug_state, debug_step_over, debug_step_into, debug_step_out, debug_resume, debug_pause, debug_run_to_cursor, debug_stop, evaluate_expression, get_stack_frames, get_variables, create_run_config, modify_run_config, delete_run_config |
| VCS | git_status, git_blame, git_diff, git_log, git_branches, git_show_file, git_show_commit, git_stash_list, git_merge_base, git_file_history |
| Spring & Framework | spring_context, spring_endpoints, spring_bean_graph, spring_config, jpa_entities, project_modules, maven_dependencies, maven_properties, maven_plugins, maven_profiles, spring_version_info, spring_profiles, spring_repositories, spring_security_config, spring_scheduled_tasks, spring_event_listeners, spring_boot_endpoints, spring_boot_autoconfig, spring_boot_config_properties, spring_boot_actuator, gradle_dependencies, gradle_tasks, gradle_properties, maven_dependency_tree, maven_effective_pom |
| Jira | jira_get_ticket, jira_get_transitions, jira_transition, jira_comment, jira_get_comments, jira_log_work, jira_get_worklogs, jira_get_sprints, jira_get_linked_prs, jira_get_boards, jira_get_sprint_issues, jira_get_board_issues, jira_search_issues, jira_get_dev_branches, jira_start_work |
| CI/CD — Bamboo | bamboo_build_status, bamboo_get_build, bamboo_trigger_build, bamboo_get_build_log, bamboo_get_test_results, bamboo_stop_build, bamboo_cancel_build, bamboo_get_artifacts, bamboo_recent_builds, bamboo_get_plans, bamboo_get_project_plans, bamboo_search_plans, bamboo_get_plan_branches, bamboo_get_running_builds, bamboo_get_build_variables, bamboo_get_plan_variables, bamboo_rerun_failed_jobs, bamboo_trigger_stage |
| Quality — SonarQube | sonar_issues, sonar_quality_gate, sonar_coverage, sonar_search_projects, sonar_analysis_tasks, sonar_branches, sonar_project_measures, sonar_source_lines, sonar_issues_paged |
| Pull Requests — Bitbucket | bitbucket_create_pr, bitbucket_get_pr_commits, bitbucket_add_inline_comment, bitbucket_reply_to_comment, bitbucket_set_reviewer_status, bitbucket_get_file_content, bitbucket_add_reviewer, bitbucket_update_pr_title, bitbucket_get_branches, bitbucket_create_branch, bitbucket_search_users, bitbucket_get_my_prs, bitbucket_get_reviewing_prs, bitbucket_get_pr_detail, bitbucket_get_pr_activities, bitbucket_get_pr_changes, bitbucket_get_pr_diff, bitbucket_get_build_statuses, bitbucket_approve_pr, bitbucket_merge_pr, bitbucket_decline_pr, bitbucket_update_pr_description, bitbucket_add_pr_comment, bitbucket_check_merge_status, bitbucket_remove_reviewer, bitbucket_list_repos |
| Planning | create_plan, update_plan_step, ask_questions, save_memory, activate_skill, deactivate_skill |

## Tool Selection (Hybrid)

Three layers:
1. **DynamicToolSelector** — keyword scan of last 3 user messages triggers relevant tool groups
2. **RequestToolsTool** (`request_tools`) — LLM activates categories on demand (always available)
3. **ToolPreferences** — user checkboxes in Tools panel, persisted per project
4. **agent**, **delegate_task**, and **request_tools** cannot be disabled (added after `removeAll(disabledTools)`)
5. Tool set stabilizes per session — tools only expand across messages, never shrink

## Context Management

- **Tool results**: Full content in context (2000 lines / 50KB cap via ToolOutputStore). Full content saved to disk for re-reads after pruning.
- **Facts Store**: Compression-proof append-only log of verified findings (FILE_READ, EDIT_MADE, CODE_PATTERN, ERROR_FOUND, COMMAND_RESULT, DISCOVERY). Injected as `factsAnchor`. Deduped by (type, path), capped at 50 entries. Think tool also records DISCOVERY facts.
- **Phase 0 (smart pruning)**: Deduplication (keep latest file read, respects edit boundaries), error purging (truncate failed tool args after 4 turns), write superseding (compact writes confirmed by subsequent reads). Zero information loss.
- **Phase 1 (tiered tool result pruning)**: Three tiers:
  - FULL: within 40K protection window — kept intact
  - COMPRESSED: within 60K window — first 20 + last 5 lines kept
  - METADATA: beyond both — rich placeholder (tool name, args, preview, disk path, recovery hint)
  - Protected tools (agent, delegate_task, create_plan, etc.) are NEVER pruned
  - Minimum savings threshold: skip results under 200 tokens
- **Phase 2 (summarization)**: Structured LLM summary (Goal/Discoveries/Accomplished/Files template) with [APPROX] markers. Fallback summarizer preserves first 10 lines of tool results (8K char cap).
- **Compression boundary**: After any compression, a `[CONTEXT COMPRESSED]` marker warns LLM earlier content is approximate.
- **Compression trigger**: 93% of effective budget (auto-compress in addMessage)
- **Budget thresholds**: OK (<80%), COMPRESS (80-88%), NUDGE (88-93%), STRONG_NUDGE (93-97%), TERMINATE (>97%)
- **Middle-truncation**: Command and git output keeps first 60% + last 40%, truncating verbose middle.
- **System messages**: Old LoopGuard/budget warnings compressible, capped at 2 active warnings
- **Orphan protection**: When compression drops an assistant tool_call, its tool result is also dropped
- **Re-read tracking**: Cleared after pruning events so agent can re-read pruned files

## Tool Execution

- **Read-only tools**: Execute in parallel (read_file, search_code, diagnostics, etc.)
- **Write tools**: Execute sequentially (edit_file, run_command)
- **Doom loop detection**: 3 identical tool calls = warning injected as system message
- **File re-read detection**: Warns when reading a file already in context; cleared on edit
- **Context overflow**: Compress + REPLAY the failed request (OpenCode pattern)

## Error Handling

- **API retry**: 5 attempts, exponential backoff with jitter (base 1s, max 30s), retries on 429 AND 5xx
- **Context overflow**: Phase 1 prune + Phase 2 compress + replay
- **Streaming**: Heuristic token estimate when API returns usage: null

## Ralph Loop Patterns

Four structural patterns integrated from the Ralph Loop technique for improved reliability:

1. **Learned Guardrails** — `GuardrailStore` persists failure patterns to `.workflow/agent/guardrails.md`. Auto-recorded from doom loops and circuit breakers. Manually recorded via `save_memory(type="guardrail")`. Loaded into system prompt and compression-proof `guardrailsAnchor`.

2. **Pre-Edit Search Enforcement** — `LoopGuard.checkPreEditRead()` hard-gates `edit_file` calls. If the file hasn't been read in the current session, the edit returns an error forcing the LLM to read first. Always on, not configurable.

3. **Backpressure Gates** — `BackpressureGate` tracks edits and injects verification nudges after every N edits (default 3). Escalates to stronger nudge if ignored. Test/build failures generate structured `<backpressure_error>` feedback.

4. **Context Rotation** — When budget hits TERMINATE (97%), instead of hard-failing, externalizes state to `rotation-state.json` (goal, accomplishments, remaining work, files, guardrails, facts) and returns `ContextRotated`. AgentController shows rotation summary. Only works when an active plan exists; falls back to `Failed` otherwise.

## Token Management

- Token display shows current context window fill (contextManager.currentTokens), not cumulative API total
- Token reconciliation uses API's promptTokens as authoritative (no stale reservation subtraction)
- reservedTokens recalculated when tool set changes

## Interactive UI

- **Plan card** — `create_plan` renders JCEF plan card with step status icons (○◉✓✗), per-step comments, approve/revise buttons. Uses `suspendCancellableCoroutine` for non-blocking approval.
- **Plan editor tab** — Full-screen `FileEditor` with `JBCefBrowser`, clickable file links, comment textareas. Opens alongside plan card.
- **Question wizard** — `ask_questions` renders inline wizard with single/multi-select options, back/skip/next navigation, "Chat about this" (JCEF textarea), summary page with edit-any-question.
- **Tools panel** — Categorized tool checkboxes with 4-tab detail view (Description, Parameters, Schema, Example).

## Plan Persistence (Three Layers)

1. **Disk** — `plan.json` in session directory (`PlanPersistence`)
2. **Context** — `<active_plan>` system message with structured summary, updated in-place (`PlanAnchor` + `ContextManager.planAnchor`)
3. **UI** — Editor tab + chat card, real-time step updates

## Conversation Persistence

- Messages: `{systemPath}/workflow-agent/sessions/{sessionId}/messages.jsonl` (append-only)
- Metadata: `{sessionId}/metadata.json`
- Plan: `{sessionId}/plan.json`
- Checkpoints: `{sessionId}/checkpoint.json`
- Global index: `GlobalSessionIndex` (app-level `PersistentStateComponent`)

## Cross-Session Memory

- Location: `{projectBasePath}/.workflow/agent/memory/`
- Index: `MEMORY.md` (first 200 lines loaded at session start)
- Topic files: `{topic}.md` (loaded inline after index, most recent first)
- LLM saves via `save_memory` tool, loaded via `AgentMemoryStore.loadMemories()`
- Injected into system prompt as `<agent_memory>` section
- `think` tool: no-op reasoning pause, proven 54% improvement on complex tasks (Anthropic data)

## Interactive Debugging

Agent has full programmatic access to IntelliJ's debugger via `AgentDebugController`:
- **Breakpoints**: Set/remove/list line breakpoints with conditions, log expressions, temporary flags
- **Session control**: Launch debug sessions, step over/into/out, resume, pause, run-to-cursor, stop
- **Inspection**: Get debug state, stack frames, variables (recursive with depth control), evaluate expressions
- **Run configs**: Create/modify/delete IntelliJ run configurations (`[Agent]` prefix for safety)
- **Async pattern**: `MutableSharedFlow(replay=1)` wraps XDebugger's callback-based API into coroutines
- **Skills**: `systematic-debugging` (updated with escalation) + `interactive-debugging` (LLM-only, activated on escalation)

## User-Extensible Skills

- Format: SKILL.md with YAML frontmatter (Agent Skills standard)
- Project: `{projectBasePath}/.workflow/skills/{name}/SKILL.md`
- User: `~/.workflow-orchestrator/skills/{name}/SKILL.md`
- Project overrides user if same name
- Discovery: descriptions loaded at session start, full content on activation
- Invocation: `/skill-name args` in chat, toolbar dropdown, or LLM calls `activate_skill`
- Active skill injected as `<active_skill>` system message (compression-proof via `skillAnchor`)
- Built-in skills: `systematic-debugging`, `interactive-debugging`, and `create-skill` ship with the plugin from resources
- Supporting files: non-SKILL.md files in skill directory listed via `getSupportingFiles()`

**Frontmatter fields:**
| Field | Default | Description |
|-------|---------|-------------|
| `name` | directory name | Skill identifier, becomes /slash-command |
| `description` | -- | When to use. LLM uses this for auto-invocation |
| `disable-model-invocation` | false | true = only user can invoke, hidden from LLM |
| `user-invocable` | true | false = only LLM can invoke, hidden from / menu |
| `allowed-tools` | null | Hard tool whitelist when skill active (overrides all selection) |
| `preferred-tools` | [] | Soft tool preference (additive, not restrictive) |
| `context` | -- | "fork" = run in isolated WorkerSession (10 iter, 5 min timeout) |
| `agent` | -- | Subagent type when context: fork |
| `argument-hint` | -- | Autocomplete hint for arguments |

**Substitutions:** `$ARGUMENTS`, `$1`-`$N`, `${CLAUDE_SKILL_DIR}`
**Dynamic injection:** `` !`command` `` runs shell at preprocessing time (10s per cmd, 30s total, 10K cap)
**Description budget:** 2% of context window (max 16K chars). Excess skills hidden with warning.
**Context fork:** Skills with `context: fork` execute in a fresh `WorkerSession` with scoped tools, returning a summary to the orchestrator.

## Agent Tool (Subagent Management)

The `agent` tool spawns, resumes, and manages subagent workers:

**Spawn:** `agent(description="...", prompt="...", subagent_type="coder")`
**Background:** `agent(description="...", prompt="...", run_in_background=true)` — returns immediately with agentId
**Resume:** `agent(resume="agentId", prompt="continue with authorization module")` — continues with full previous context
**Kill:** `agent(kill="agentId")` — cancels a running background agent

**Transcript persistence:** All worker conversations are saved to `{sessionDir}/subagents/agent-{id}.jsonl`. Resume reconstructs the full conversation context from the transcript.

**Background notifications:** When a background agent completes, the parent is notified via a system message injected into the conversation context (`<background_agent_completed>` tag) and a UI status message in the chat panel.

**Built-in types:** general-purpose, explorer (PSI-powered, read-only, thoroughness: quick/medium/very thorough), coder, reviewer, tooler
**Custom types:** Any agent defined in `.workflow/agents/{name}.md`

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
| `max-turns` | 10 | Max agentic iterations |
| `skills` | [] | Skills preloaded at startup |
| `memory` | none | Persistent memory: user/project/local |

**Memory locations:**
- `user`: `~/.workflow-orchestrator/agent-memory/{name}/`
- `project`: `.workflow/agent-memory/{name}/`
- `local`: `.workflow/agent-memory-local/{name}/`

Invoked via `agent(subagent_type="name", prompt="...")`. LLM sees available subagent descriptions in system prompt. Legacy `delegate_task(agent="name", task="...")` still supported but deprecated.

## Security

- **PathValidator** — canonical path comparison prevents traversal (`../../etc/passwd`)
- **CredentialRedactor** — redacts private keys, AWS/GitHub/Sourcegraph tokens from output
- **OutputValidator** — flags sensitive data in LLM responses
- **InputSanitizer** — validates tool input parameters
- **External data tags** — tool results wrapped in `<external_data>` for prompt injection defense
- **Plan editor** — file click handler validates path within project basePath

## Rich Chat UI

**Full JCEF Architecture:**
- Entire agent tab is a single `JBCefBrowser` — toolbar, chat, and input all rendered in HTML/CSS/JS
- `AgentDashboardPanel` is a thin Swing wrapper hosting `AgentCefPanel`
- 24 `JBCefJSQuery` bridges for JS→Kotlin communication
- Same HTML page reusable in editor tabs and popup windows
- Bolt-style glassmorphic input bar with gradient glow, auto-expand, model/plan/skills chips

JCEF-based (Chromium) rendering with bundled libraries (zero CDN dependency):

**Core (always loaded, ~32KB gzipped):**
- marked.js — Markdown parsing with GFM, custom renderers
- Prism.js — Syntax highlighting (297 languages, on-demand autoloader)
- DOMPurify — XSS prevention on all LLM-rendered HTML
- ansi_up — ANSI terminal colors in command output

**Lazy-loaded (on first use):**
- dagre.js (~284KB) — Graph layout engine for ```flow animated data flow diagrams
- Mermaid.js (~250KB) — Diagrams from ```mermaid code blocks (animated entrance + flowing edges)
- KaTeX (~320KB) — LaTeX math from $...$ and $$...$$ expressions
- Chart.js (~65KB) — Interactive animated charts from ```chart JSON configs
- diff2html (~40KB) — Side-by-side diffs from ```diff code blocks

**Animated visualization formats (all have animations — no static output):**
- ```flow — Animated data flow diagrams: dagre-laid-out SVG with node entrance, edge draw, and glowing particle dots that continuously flow along paths (via SVG `<animateMotion>`). LLM outputs JSON `{nodes, edges, title, direction}`.
- ```mermaid — Staggered node entrance + edge draw animation, then persistent subtle flowing dash animation on all edges after entrance completes.
- ```chart — Chart.js with injected `easeOutQuart` animation, staggered per data point (80ms) and dataset (200ms).
- ```visualization / ```viz — Custom HTML/CSS/JS in sandboxed iframe with theme variables.
- ```diff / ```patch — Side-by-side diffs via diff2html.

**Streaming-aware rendering:** Open (unclosed) rich code blocks during streaming show skeleton placeholders instead of raw syntax. `_detectOpenRichBlock()` tracks fence pairs; `_renderStreamingMarkdown()` renders completed content normally and shows shimmer skeleton for in-progress blocks.

**Working indicator:** Bouncing dots + rotating text ("Working", "Thinking", "Analyzing"...) above input bar. Shows on sendMessage(), hides on first token/tool call.

**IDE-native features:**
- Click-to-navigate file paths (opens file at line in IDE editor)
- Jira card embeds with status/priority/assignee
- Sonar quality gate badges with metrics
- @ mention autocomplete: type @ to reference files, folders, symbols, tools, skills.
  @file reads content via Document API (sees unsaved changes) and injects into LLM context.
  @folder injects directory tree. Content stored as compression-proof mentionAnchor.
  Budget: 500 lines / 20K per file, 50K total.
- Toast notifications, skeleton loading, timeline visualization
- Sortable/filterable tables, tabbed content, progress bars

**Resource serving:** `CefResourceSchemeHandler` serves all resources from plugin JAR via `http://workflow-agent/` scheme, loading from `webview/dist/` (Vite build output). CSP enforced: `connect-src: 'none'` (no outbound network).

## React Webview Architecture

The chat UI is a React + TypeScript app built with Vite, located in `agent/webview/`.

**Stack:** React 19, TypeScript, Zustand (state), Tailwind CSS, Vite

**Build:**
```bash
cd agent/webview && npm run build    # Output: agent/src/main/resources/webview/dist/
```

**Directory structure:**
```
agent/webview/
  src/
    bridge/         # JCEF bridge protocol (jcef-bridge.ts, globals.d.ts, types.ts)
    components/     # React components (chat/, rich/, common/, input/)
    stores/         # Zustand stores (chatStore, themeStore, settingsStore)
    styles/         # Tailwind + theme CSS
    App.tsx         # Root component
    main.tsx        # Entry point
  vite.config.ts    # Vite config (outputs to ../src/main/resources/webview/dist/)
```

**Bridge protocol:**
- **Kotlin -> JS (42 functions):** `AgentCefPanel.callJs()` invokes global JS functions registered by `initBridge()` in `jcef-bridge.ts`. Functions map directly to Zustand store actions.
- **JS -> Kotlin (26 functions):** `kotlinBridge` object wraps `JBCefJSQuery` bridges injected as `window._xxx` globals. React components call `kotlinBridge.sendMessage()`, etc.
- **Editor tab popout:** `openInEditorTab(type, content)` sends visualization content to Kotlin via `_openInEditorTab` bridge, which opens an `AgentVisualizationEditor` tab.

**Key files:**
- `bridge/jcef-bridge.ts` — All bridge function definitions and initialization
- `bridge/globals.d.ts` — TypeScript declarations for Kotlin-injected window globals
- `stores/chatStore.ts` — Primary state: messages, streaming, plans, questions, tool calls
- `stores/themeStore.ts` — IDE theme variables synced from Kotlin
- `stores/settingsStore.ts` — Visualization settings (enabled, maxHeight, etc.)

**Visualization popout:** `AgentVisualizationTab.kt` provides `FileEditor` + `FileEditorProvider` + `LightVirtualFile` for opening visualizations (mermaid, chart, flow, etc.) in IDE editor tabs.

## Testing

```bash
./gradlew :agent:test                    # All agent tests (~470)
./gradlew :agent:test --tests "...Test"  # Specific test class
./gradlew :agent:clean :agent:test --rerun --no-build-cache  # Clean rebuild (needed after constructor changes)
```

Key test patterns: JUnit 5 + MockK + `@TempDir` for file I/O, `runTest` for coroutines, `mockk<Project>` for IntelliJ services.

## IntelliJ-Native APIs

Core tools (read, edit, search) use IntelliJ Document API and VFS for undo support, unsaved change visibility, and editor sync.

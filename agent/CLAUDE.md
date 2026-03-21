# :agent Module

AI coding agent with ReAct loop, LLM-controlled delegation, interactive planning, and 63 tools.

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
        → BudgetEnforcer (COMPRESS at 40%, NUDGE at 60%, STRONG_NUDGE at 75%, TERMINATE at 90%)
        → LoopGuard (loop detection, error nudges, auto-verification)
        → Tool execution with optional ApprovalGate
```

## Key Components

- **SingleAgentSession** — Core ReAct loop. Budget enforcement, nudge injection, tool call processing, context reduction on API errors. Calls `compressWithLlm(brain)` for LLM-powered compression. Truncated tool call recovery — detects invalid JSON when finishReason=length, asks LLM to retry with smaller operation.
- **ConversationSession** — Long-lived session across user messages. Owns `ContextManager`, `PlanManager`, `QuestionManager`, `WorkingSet`, `RollbackManager`. Persisted to JSONL.
- **ContextManager** — Two-threshold compression (T_max=70%, T_retained=40%). `compressWithLlm()` uses LLM for tool result summarization, truncation for plain text. Dedicated `planAnchor` slot survives compression. Token reconciliation with API's actual `prompt_tokens` after each LLM call (subtracts `reservedTokens` to avoid double-counting tool definition overhead). Not thread-safe — must be accessed from a single coroutine context.
- **BudgetEnforcer** — Four-status budget monitoring: OK, COMPRESS, NUDGE, STRONG_NUDGE, TERMINATE.
- **DelegateTaskTool** — LLM-controlled worker spawning. Fresh `WorkerSession` per delegation with scoped tools, 5-min timeout, LocalHistory rollback on failure. Max 5 workers, retry limit 2 per task.
- **WorkerSession** — Scoped ReAct loop (max 10 iterations) with parent Job cancellation support.

## Tools (63 total, 9 categories)

| Category | Tools |
|----------|-------|
| Core (always active) | read_file, edit_file, search_code, run_command, glob_files, diagnostics, format_code, optimize_imports, file_structure, find_definition, find_references, type_hierarchy, call_hierarchy, delegate_task, think |
| IDE Intelligence | run_inspections, refactor_rename, list_quickfixes, compile_module, run_tests |
| VCS & Navigation | git_status, git_blame, find_implementations |
| Spring & Framework | spring_context, spring_endpoints, spring_bean_graph, spring_config, jpa_entities, project_modules, maven_dependencies, maven_properties, maven_plugins, maven_profiles, spring_version_info, spring_profiles, spring_repositories, spring_security_config, spring_scheduled_tasks, spring_event_listeners |
| Jira | jira_get_ticket, jira_get_transitions, jira_transition, jira_comment, jira_get_comments, jira_log_work |
| CI/CD — Bamboo | bamboo_build_status, bamboo_get_build, bamboo_trigger_build, bamboo_get_build_log, bamboo_get_test_results |
| Quality — SonarQube | sonar_issues, sonar_quality_gate, sonar_coverage, sonar_search_projects, sonar_analysis_tasks, sonar_project_health |
| Pull Requests — Bitbucket | bitbucket_create_pr |
| Planning | create_plan, update_plan_step, ask_questions, save_memory, activate_skill, deactivate_skill |

## Tool Selection (Hybrid)

Three layers:
1. **DynamicToolSelector** — keyword scan of last 3 user messages triggers relevant tool groups
2. **RequestToolsTool** (`request_tools`) — LLM activates categories on demand (always available)
3. **ToolPreferences** — user checkboxes in Tools panel, persisted per project
4. **delegate_task** and **request_tools** cannot be disabled (added after `removeAll(disabledTools)`)

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

## User-Extensible Skills

- Format: SKILL.md with YAML frontmatter (Agent Skills standard)
- Project: `{projectBasePath}/.workflow/skills/{name}/SKILL.md`
- User: `~/.workflow-orchestrator/skills/{name}/SKILL.md`
- Project overrides user if same name
- Discovery: descriptions loaded at session start, full content on activation
- Invocation: `/skill-name args` in chat, toolbar dropdown, or LLM calls `activate_skill`
- Active skill injected as `<active_skill>` system message (compression-proof via `skillAnchor`)
- Built-in skills: `systematic-debugging` and `create-skill` ship with the plugin from resources
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

Invoked via `delegate_task(agent="name", task="...")`. LLM sees available subagent descriptions in system prompt.

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
- Mermaid.js (~250KB) — Diagrams from ```mermaid code blocks (flowchart, sequence, class, ER, Gantt, git graph)
- KaTeX (~320KB) — LaTeX math from $...$ and $$...$$ expressions
- Chart.js (~65KB) — Interactive charts from ```chart JSON configs
- diff2html (~40KB) — Side-by-side diffs from ```diff code blocks

**IDE-native features:**
- Click-to-navigate file paths (opens file at line in IDE editor)
- Jira card embeds with status/priority/assignee
- Sonar quality gate badges with metrics
- @ mention autocomplete: type @ to search files (VFS), symbols (PSI), tools, skills — mentions provide context to the LLM
- Toast notifications, skeleton loading, timeline visualization
- Sortable/filterable tables, tabbed content, progress bars

**Resource serving:** `CefResourceSchemeHandler` serves all resources from plugin JAR via `http://workflow-agent/` scheme. CSP enforced: `connect-src: 'none'` (no outbound network).

## Testing

```bash
./gradlew :agent:test                    # All agent tests (~470)
./gradlew :agent:test --tests "...Test"  # Specific test class
./gradlew :agent:clean :agent:test --rerun --no-build-cache  # Clean rebuild (needed after constructor changes)
```

Key test patterns: JUnit 5 + MockK + `@TempDir` for file I/O, `runTest` for coroutines, `mockk<Project>` for IntelliJ services.

## IntelliJ-Native APIs

Core tools (read, edit, search) use IntelliJ Document API and VFS for undo support, unsaved change visibility, and editor sync.

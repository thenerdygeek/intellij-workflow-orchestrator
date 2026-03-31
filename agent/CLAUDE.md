# :agent Module

AI coding agent with ReAct loop, LLM-controlled delegation, interactive planning, and 104 tools.

## LLM API

Uses Sourcegraph Enterprise's OpenAI-compatible API:
- Endpoint: `/.api/llm/chat/completions`
- Auth: `token` scheme via `Authorization: token <sourcegraph-access-token>`
- Constraints: 190K input tokens (configurable), no `system` role (converted to user with `<system_instructions>` tags), no `tool_choice`, strict user/assistant alternation
- Output limit varies per model — no hardcoded clamp. User configures maxOutputTokens in settings.
- Model: Auto-resolved from `GET /.api/llm/models` on first use via `ModelCache`. Priority: Anthropic Opus thinking > Opus > Sonnet. No hardcoded defaults.
- Message sanitization in `SourcegraphChatClient.sanitizeMessages()`: system→user, tool→user with `<tool_result>` tags, consecutive same-role merging

## Architecture

```
AgentController (UI entry point)
  → ConversationSession (long-lived, owns context + plan + question managers)
    → AgentOrchestrator.executeTask()
      → SingleAgentSession.execute() (ReAct loop, max 50 iterations)
        → BudgetEnforcer (COMPRESS at 80%, TERMINATE at 97%)
        → LoopGuard (loop detection, error nudges, auto-verification)
        → Tool execution with optional ApprovalGate
```

## Key Components

- **SingleAgentSession** — Core ReAct loop. Budget enforcement, nudge injection, tool call processing, context reduction on API errors. Calls `compressWithLlm(brain)` for LLM-powered compression. Truncated tool call recovery — detects invalid JSON when finishReason=length, asks LLM to retry with smaller operation. Context budget awareness (system_warning at >50% fill). Graceful degradation (80% iterations = wrap-up nudge, 95% = force text-only). Mid-loop cancellation support. Parallel read-only tool execution (via coroutineScope+async). Context overflow replay (compress + retry same request). Doom loop detection before each tool call. Streaming token estimate when usage is null.
- **ConversationSession** — Long-lived session across user messages. Owns `ContextManager`, `PlanManager`, `QuestionManager`, `WorkingSet`, `RollbackManager`. Persisted to JSONL.
- **ContextManager** — Sliding window compression (Cline-inspired). Phase 1 prunes old tool results (protects last 30K tokens). Phase 2 removes 50% of conversation history via sliding window — preserves system prompt and first user-assistant exchange, removes oldest half of remaining messages, LLM-summarizes dropped content with structured template (Goal/Instructions/Discoveries/Accomplished/Relevant Files). Fallback to truncation summarizer for text-only or on LLM error. Anchored summaries capped at 3 (consolidated when exceeded). Dedicated `planAnchor` slot survives compression. Token reconciliation with API's actual `prompt_tokens` after each LLM call. Old system messages (LoopGuard reminders, budget warnings) are compressible — only the original system prompt is protected. Not thread-safe — must be accessed from a single coroutine context.
- **BudgetEnforcer** — Three-status budget monitoring (Cline-inspired single compression threshold): OK (<80%), COMPRESS (80-97%), TERMINATE (>97%). COMPRESS fires once per crossing (re-arms when utilization drops back below 80%).
- **GuardrailStore** — Persistent learned constraints (`~/.workflow-orchestrator/{proj}/agent/guardrails.md`). Auto-recorded from doom loops/circuit breakers, loaded into system prompt and compression-proof anchor.
- **BackpressureGate** — Edit-count tracker that injects verification nudges after N edits without running diagnostics/tests.
- **SelfCorrectionGate** — Verify-reflect-retry loop. Tracks per-file edit→verification pairs. After each edit, demands diagnostics. On verification failure, injects structured `<self_correction>` reflection prompt with error details and retry guidance. Blocks task completion until all edited files are verified or max retries (3) exhausted. Works alongside BackpressureGate and LoopGuard.
- **CompletionGatekeeper** — Orchestrates 3 completion gates before accepting task completion: Plan (blocks if plan has incomplete steps, escalates after 3 blocks without progress), SelfCorrectionGate (blocks if unverified edits), LoopGuard (blocks if unverified files). Force-accepts after 5 total blocked attempts. `attempt_completion` tool delegates to this.
- **AttemptCompletionTool** (`attempt_completion`) — Explicit completion signal. LLM must call this to end the session. In normal mode, text-only responses (no tool calls) trigger escalating nudges (up to `MAX_NO_TOOL_NUDGES=4`) demanding `attempt_completion`. Implicit completion via CompletionGatekeeper is only allowed in `forceTextOnly` mode (activated after 4 nudges ignored, malformed retries exhausted, or iteration 95%+). Not available to WorkerSession — workers use `worker_complete` instead.
- **WorkerCompleteTool** (`worker_complete`) — Lightweight completion signal for worker sessions (subagents). Analogous to `attempt_completion` but without CompletionGatekeeper dependency. Returns `isCompletion=true` to exit the WorkerSession ReAct loop immediately. Available to all WorkerTypes (ORCHESTRATOR, ANALYZER, CODER, REVIEWER, TOOLER). No collision with `attempt_completion` — that tool is injected directly into SingleAgentSession's tool set, not registered in ToolRegistry.
- **RotationState** — Serializable context handoff state for graceful session rotation when budget is exhausted.
- **SpawnAgentTool** (`agent`) — Primary tool for spawning subagents, matching Claude Code's Agent tool design. Only `description` and `prompt` required. `subagent_type` selects built-in (general-purpose/explorer/coder/reviewer/tooler) or custom agents from `.workflow/agents/`. Defaults to general-purpose. Explorer type uses PSI-first search strategy with thoroughness calibration (quick/medium/very thorough) and is restricted to read-only tools only (no debug, config, or edit tools).
- **DelegateTaskTool** (`delegate_task`) — [DEPRECATED] Legacy worker spawning tool. Use `agent` tool instead. Kept for backward compatibility.
- **WorkerSession** — Scoped ReAct loop (max 10 iterations) with parent Job cancellation support.

## System Prompt Structure (`PromptAssembler`)

Assembled dynamically per turn. Section order follows primacy/recency attention patterns:

**Primacy zone** (highest compliance):
1. `CORE_IDENTITY` — role, capabilities, persona
2. `PERSISTENCE_AND_COMPLETION` — session durability, `attempt_completion` requirement
3. `TOOL_POLICY` — tool usage rules, read-before-edit, verification

**Context zone** (reference data, conditionally included):
4. `<project_context>` — name, path, framework
5. `<project_repositories>` — repo info
6. `<repo_map>` — file structure
7. `<core_memory>` — tier-1 memory (always if non-empty)
8. `<agent_memory>` — legacy markdown memory
9. Guardrails context
10. `<available_agents>` — **always injected**: built-in agents (general-purpose, explorer, coder, reviewer, tooler) + any custom agents from `.workflow/agents/`
11. `<available_skills>` — skill descriptions (if any)
12. `<previous_results>` — orchestration step context

**Recency zone** (highest recall):
13. `PLANNING_RULES` (or `FORCED_PLANNING_RULES` in plan mode)
14. `DELEGATION_RULES` — when/how to spawn subagents
15. `MEMORY_RULES` — when to save to each memory tier
16. `CONTEXT_MANAGEMENT_RULES` — budget awareness
17. `RENDERING_RULES_COMPACT` — rich UI formatting (skipped in plain-text mode)
18. `FEW_SHOT_EXAMPLES` — concrete tool call examples
19. `RULES` — general behavioral rules
20. `<integration_rules>` — **conditional**: niche tips for Jira/Bamboo/Sonar/Bitbucket/PSI/Debug tools, only included when those tools are active
21. `COMMUNICATION` — response style guidelines
22. `BOOKEND` — closing reinforcement of identity + key constraints

**Removed sections** (consolidated or eliminated): `EFFICIENCY_RULES`, `THINKING_RULES`, `MENTION_RULES`, `critical_reminders`, verbose `RENDERING_RULES`.

## Tools (57 registered, 9 meta-tools consolidating 138 actions)

| Category | Tools |
|----------|-------|
| Core (always active) | read_file, edit_file, search_code, run_command, glob_files, diagnostics, problem_view, format_code, optimize_imports, file_structure, find_definition, find_references, type_hierarchy, call_hierarchy, get_annotations, get_method_body, agent, delegate_task (deprecated), think, request_tools, project_context |
| Process Interaction | send_stdin, kill_process, ask_user_input |
| PSI / Code Intelligence | type_inference, structural_search, dataflow_analysis, read_write_access, test_finder |
| IDE Intelligence | run_inspections, refactor_rename, list_quickfixes, find_implementations |
| Runtime & Debug | **runtime** (9 actions: get_run_configurations, create/modify/delete_run_config, get_running_processes, get_run_output, get_test_results, run_tests, compile_module), **debug** (24 actions: breakpoints, stepping, inspection, hotswap, attach) |
| VCS | **git** (11 actions: status, blame, diff, log, branches, show_file, show_commit, stash_list, merge_base, file_history, shelve) |
| Spring & Framework | **spring** (15 actions: context, endpoints, bean_graph, config, version_info, profiles, repositories, security_config, scheduled_tasks, event_listeners, boot_endpoints/autoconfig/config_properties/actuator, jpa_entities) |
| Build Systems | **build** (11 actions: maven_dependencies/properties/plugins/profiles/dependency_tree/effective_pom, gradle_dependencies/tasks/properties, project_modules, module_dependency_graph) |
| Jira | **jira** (15 actions: get_ticket, search_issues, transition, comment, log_work, get_worklogs, get_sprints, get_boards, get_sprint/board_issues, get_linked_prs, get_dev_branches, start_work) |
| CI/CD — Bamboo | **bamboo** (18 actions: build_status, get/trigger/stop/cancel_build, get_build_log, test_results, artifacts, recent_builds, plans, running_builds, variables, rerun_failed, trigger_stage) |
| Quality — SonarQube | **sonar** (9 actions: issues, quality_gate, coverage, search_projects, analysis_tasks, branches, project_measures, source_lines, issues_paged) |
| Pull Requests — Bitbucket | **bitbucket** (26 actions: create/approve/merge/decline_pr, comments, reviewers, branches, diff, build_statuses, repos) |
| Memory | core_memory_read, core_memory_append, core_memory_replace, archival_memory_insert, archival_memory_search, conversation_search, save_memory |
| Skills | activate_skill, deactivate_skill |
| Database | db_list_profiles, db_query, db_schema |
| Planning | enable_plan_mode, create_plan, update_plan_step, ask_questions, attempt_completion |

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
- **Compression trigger**: 85% of effective budget (auto-compress in addMessage)
- **Budget thresholds**: OK (<80%), COMPRESS (80-97%), TERMINATE (>97%)
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

1. **Learned Guardrails** — `GuardrailStore` persists failure patterns to `~/.workflow-orchestrator/{proj}/agent/guardrails.md`. Auto-recorded from doom loops and circuit breakers. Manually recorded via `save_memory(type="guardrail")`. Loaded into system prompt and compression-proof `guardrailsAnchor`.

2. **Pre-Edit Search Enforcement** — `LoopGuard.checkPreEditRead()` hard-gates `edit_file` calls. If the file hasn't been read in the current session, the edit returns an error forcing the LLM to read first. Always on, not configurable.

3. **Backpressure Gates** — `BackpressureGate` tracks edits and injects verification nudges after every N edits (default 3). Escalates to stronger nudge if ignored. Test/build failures generate structured `<backpressure_error>` feedback.

5. **Self-Correction Loop** — `SelfCorrectionGate` enforces verify-reflect-retry per edited file. After each `edit_file`, demands `diagnostics`. On failure, injects `<self_correction>` reflection prompt (what failed, why, how to fix). Caps at 3 retries per file. Blocks completion until all edits verified or retries exhausted.

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

## Conversation Persistence & Durable Execution

- Messages: `~/.workflow-orchestrator/{proj}/agent/sessions/{sessionId}/messages.jsonl` (append-only)
- Metadata: `{sessionId}/metadata.json`
- Plan: `{sessionId}/plan.json`
- Checkpoints: `{sessionId}/checkpoint.json` — saved after every iteration with: iteration, tokensUsed, editedFiles, hasPlan, lastActivity, persistedMessageCount
- Traces: `{sessionId}/traces/trace.jsonl`
- API Debug: `{sessionId}/api-debug/call-NNN-{request|response|error}.txt`
- Global index: `GlobalSessionIndex` (app-level `PersistentStateComponent`)

**Durable execution flow:**
1. After each ReAct iteration, `onCheckpoint` callback fires → `ConversationSession.saveCheckpoint()` persists messages (incremental JSONL append) + checkpoint state
2. On IDE crash: session stays "active" in GlobalSessionIndex
3. On next startup: `AgentStartupActivity` detects "active" sessions, marks as "interrupted", shows notification with "Resume Session" action
4. On resume: `ConversationSession.load()` replays JSONL messages, loads checkpoint, injects `<session_resumed>` context (edited files, iteration count, plan status) so the agent can orient and continue

## Three-Tier Memory System

### Tier 1: Core Memory (always in prompt, 4KB)
- Location: `~/.workflow-orchestrator/{proj}/agent/core-memory.json`
- Fixed-size key-value store, injected as `<core_memory>` in system prompt
- Self-editable by agent via `core_memory_read`, `core_memory_append`, `core_memory_replace` tools
- Use for: build system quirks, key file paths, user preferences, active constraints
- Loaded at session start by `CoreMemory.forProject()`

### Tier 2: Archival Memory (searchable, unlimited)
- Location: `~/.workflow-orchestrator/{proj}/agent/archival/store.json`
- JSON-backed store with LLM-generated tags for keyword search
- Insert via `archival_memory_insert` (requires tags for searchability)
- Search via `archival_memory_search` (keyword matching with 3x tag boost)
- Types: error_resolution, code_pattern, decision, api_behavior, project_convention, agent_memory
- Cap: 5000 entries, oldest evicted when full
- No ML models — uses tag-boosted keyword matching (sub-millisecond for <5K entries)

### Tier 3: Conversation Recall (past session search)
- Searches JSONL transcripts across all past sessions
- `conversation_search` tool for keyword search
- Returns matching messages with session context
- Read-only — sessions persisted by ConversationStore

### Legacy: Markdown Memory
- Location: `~/.workflow-orchestrator/{proj}/agent/memory/`
- Index: `MEMORY.md` (first 200 lines loaded at session start)
- LLM saves via `save_memory` tool, loaded via `AgentMemoryStore.loadMemories()`
- Injected as `<agent_memory>` section (kept for backward compatibility)

### Memory Tools (6 new + 1 legacy)
| Tool | Tier | Description |
|------|------|-------------|
| `core_memory_read` | Core | Read current core memory block |
| `core_memory_append` | Core | Add/update entry in core memory |
| `core_memory_replace` | Core | Replace or delete core memory entry |
| `archival_memory_insert` | Archival | Store long-term knowledge with tags |
| `archival_memory_search` | Archival | Keyword search over archival store |
| `conversation_search` | Recall | Search past session transcripts |
| `save_memory` | Legacy | Save markdown memory file |

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

**Transcript persistence:** All worker conversations are saved to `~/.workflow-orchestrator/{proj}/agent/sessions/{sessionId}/subagents/agent-{id}.jsonl`. Resume reconstructs the full conversation context from the transcript.

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

## Evaluation & Observability

### SessionScorecard

End-of-session quality scorecard computed at every session exit point (completed, failed, cancelled, rotated). Captures:

- **SessionScorecardMetrics** — iterations, tool calls (total + unique), errors, compressions, input/output tokens, estimated cost (Claude Sonnet pricing baseline), plan progress, self-correction stats, approvals, subagent count, duration
- **QualitySignals** — hallucination flags (OutputValidator), credential leak attempts (CredentialRedactor), doom loop triggers (LoopGuard), circuit breaker trips (AgentMetrics), guardrail hits, files edited/verified/exhausted (SelfCorrectionGate)

Computed via `SessionScorecard.compute()` which pulls from `AgentMetrics.snapshot()`, `SelfCorrectionGate.getFileStates()`, and `PlanManager.currentPlan`. Wired into `SingleAgentResult.Completed`, `Failed`, and `ContextRotated`.

### MetricsStore

Persists scorecards as JSON files for trend analysis:
- Location: `~/.workflow-orchestrator/{proj}/agent/metrics/scorecard-{sessionId}.json`
- `save(scorecard)` / `load(sessionId)` / `loadAll()` / `loadRecent(limit)`
- `getSummaryStats()` — aggregate: completion rate, avg cost, avg iterations, total quality signal counts
- `cleanup(maxAge, maxCount)` — evicts old scorecards (default: 30 days, 100 max)

Auto-persisted by `AgentOrchestrator` after each `SingleAgentSession.execute()` returns.

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

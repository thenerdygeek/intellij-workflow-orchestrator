# Research Sub-Agent — Design (v1)

**Status:** Spec — ready for review.
**Date:** 2026-05-24.
**Worktree:** `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.claude/worktrees/web-fetch-search/`.
**Prerequisite reading:** [`docs/research/2026-05-24-research-subagent-persona-survey.md`](../../research/2026-05-24-research-subagent-persona-survey.md) — the industry survey informing this design.

## 1. Purpose

Add a new bundled sub-agent persona named `research` that performs thorough external research on a topic (web search + URL fetch only, no project-code reads), then dumps a single sourced markdown report into a new per-project research folder. The parent agent receives a path to the dump, not the raw findings — keeping the parent's context window unpolluted while making research artifacts re-readable across sessions.

Existing patterns this builds on: the bundled persona system (`agent/src/main/resources/agents/*.md`), `MemoryIndex` for system-prompt auto-injection, `PathValidator` for write-root gating, the `SpawnAgentTool` invocation path.

## 2. Scope

In scope for v1:

- New bundled persona `research.md`.
- New per-project storage root `~/.workflow-orchestrator/{proj}/agent/research/` (the "research dir").
- New auto-managed index file `RESEARCH.md` inside that dir, auto-injected into the orchestrator's system prompt like `MEMORY.md`.
- `PathValidator` write-root extension to allow `create_file` / `edit_file` against the research dir.
- New `PluginSettings.enableResearchSubagent` flag (default `true`).
- Project-level Settings UI toggle for the flag.
- `/research <topic>` slash command — a SKILL.md whose body instructs the LLM to call `agent(agent_type="research", prompt=$ARGUMENTS)`.
- "Open research folder" toolbar button in the Agent tab — single button that opens the OS file browser at the research dir.

Out of scope for v1 (deferred to v2, documented in §10):

- JCEF "Research" tool-window tab with markdown preview.
- Anthropic-style citation/review verifier sub-agent (post-dump URL liveness check).
- Fan-out of multiple parallel research sub-agents from inside the persona.
- RESEARCH.md compaction / relevance-sorted truncation past 200 lines (today: hard truncate).
- Auto-dedup if the persona produces a research file for a topic that already exists.

## 3. User-facing behaviour

Three ways to invoke the research persona:

1. **From chat (slash command):** user types `/research how does OkHttp ConnectionPool work` in the agent chat input. The `$ARGUMENTS` substitution fills the topic into the skill body. The skill body is text that instructs the LLM to call the `agent` tool, which the LLM then does in its next turn.
2. **From orchestrator (autonomous):** the orchestrator decides to delegate research and calls `agent(agent_type="research", prompt="<topic>")` itself.
3. **No third surface in v1.** No status-bar widget, no menu action.

When the research sub-agent runs:

1. It does several rounds of `web_search` + `web_fetch` per the OODA loop in its persona body.
2. It writes to `~/.workflow-orchestrator/{proj}/agent/research/YYYY-MM-DD-{topic-slug}-{first6OfSessionId}.md` — `create_file` once early in the session, then `edit_file` incrementally as findings arrive (Anthropic filesystem-handoff pattern).
3. On `task_report`, it returns `summary="Researched X; dumped to {path}"` and `nextSteps=["read_file {path} for the full report"]`. The orchestrator never sees the raw findings.
4. A Kotlin-side hook (`ResearchIndex.onResearchFileCreated`) fires when `CreateFileTool` writes the new dump, atomically appending one line to `RESEARCH.md`. The persona itself never touches RESEARCH.md — eliminates the race-condition surface.

On subsequent sessions, the orchestrator's system prompt auto-injects RESEARCH.md (truncated at 200 lines) so it sees which research artifacts already exist and can `read_file` any of them on demand.

The user can:

- Browse the research dir via the toolbar "Open research folder" button (opens OS file browser).
- Disable the persona entirely via the settings checkbox (then `/research` and `agent(agent_type="research")` both return `RESEARCH_SUBAGENT_DISABLED`).

## 4. Storage layout

```
~/.workflow-orchestrator/
  {proj-slug}-{sha6}/
    agent/
      sessions/              # per-chat history (unchanged)
      memory/                # existing
        MEMORY.md
        feedback_*.md
        ...
      research/              # NEW
        RESEARCH.md          # index, ≤200 lines, auto-injected into system prompt
        2026-05-24-okhttp-connection-pool-tuning-01HZ4K.md
        2026-05-24-jcef-resource-loaders-01HZ4M.md
        ...
```

Per dump file: see §5 for the format.

`RESEARCH.md` example:

```markdown
# Research Index

- [OkHttp ConnectionPool Tuning](2026-05-24-okhttp-connection-pool-tuning-01HZ4K.md) — when 5/5min default holds; gotchas with cellular handover.
- [JCEF Resource Loaders](2026-05-24-jcef-resource-loaders-01HZ4M.md) — CefResourceSchemeHandler vs CefRequestHandler tradeoffs.
```

One line per dump. Format: `- [Title](filename.md) — one-line hook`. The `Title` and `hook` come from the dump file's frontmatter + first finding paragraph; the Kotlin hook in §6.6 derives both.

## 5. Output file format (the contract)

Every research session produces ONE file. Fixed frontmatter + 7 fixed sections so future `grep` and `read_file` consumption is predictable.

```markdown
---
topic: okhttp-connection-pool-tuning
question: "How should we configure OkHttp's ConnectionPool for our IDE plugin's HTTP traffic?"
researched-by: workflow-orchestrator research subagent
session-id: 01HZ4K...
retrieved-at: 2026-05-24T15:23:00Z
sources-consulted: 11
sources-cited: 7
---

# OkHttp ConnectionPool Tuning for IDE-Plugin HTTP

## Research question
<verbatim from the parent's prompt>

## Method
- 3 web_search queries: "okhttp connectionpool best practices", ...
- 7 web_fetch URLs: ...
- Stopped after 11 sources — last 3 fetches added no new info.

## Sources
| # | URL | Retrieved | Credibility |
|---|-----|-----------|-------------|
| 1 | https://square.github.io/okhttp/4.x/.../-connection-pool/ | 2026-05-24 | Primary (official docs) |
| 2 | https://github.com/square/okhttp/blob/master/CHANGELOG.md | 2026-05-24 | Primary (source repo) |
| 3 | https://medium.com/@author/okhttp-tips | 2026-05-24 | Tertiary, unverified |

## Findings
The default `ConnectionPool(5, 5, TimeUnit.MINUTES)` [1] is appropriate for ... [2].

## Limitations
- No primary source found for behaviour under sudden cellular-to-WiFi handover.
- Single-source for claim X — see [5] only.

## Open questions
- How does this interact with our `ConnectionSpec.MODERN_TLS`?
```

**Credibility column values** (the persona's body teaches the ladder):

- `Primary (<reason>)` — specs, RFCs, authoritative docs, published papers, official engineering blogs, source repos.
- `Secondary (<reason>)` — well-known engineering blogs with citations.
- `Tertiary (<reason>)` — community articles, Stack Overflow.
- Append `unverified` when the URL came from `web_search` results but was never subsequently `web_fetch`-ed (addresses the arxiv:2604.03173 stale-URL failure mode).

## 6. Components — what changes

### 6.1 New persona file — `agent/src/main/resources/agents/research.md`

YAML frontmatter:

```yaml
---
name: research
description: "Use for thorough external research on a topic — web searches and URL fetches that compile a sourced markdown report into the project's research folder. Returns a path to the dumped file, not the findings themselves. Invoke when the user asks for deep research or when you need authoritative external context before making a recommendation."
tools: web_fetch, web_search, create_file, edit_file, read_file, task_report
model: sonnet                # logical tier; ModelCache resolves to a concrete Anthropic Sonnet model ID at spawn time. User can override at the `agent()` call site with `model="<full-sourcegraph-model-id>"`.
memory: project
prompt-sections:
  editing-files: false       # persona only edits its own research dir, no project edits
---
```

Body sections, in order (~140-180 lines total — modeled on `explorer.md`):

1. **Role** — "You are a senior research analyst doing thorough external research on a topic for a software engineer. External sources only — you do not read project code, run commands, or call integration tools."
2. **Tools and scope** — what's available (`web_search`, `web_fetch`, `create_file` / `edit_file` / `read_file` against the research dir only, `task_report`). What's NOT (no project code reads, no shell, no other-domain tools).
3. **OODA loop per iteration** — observe → orient → decide → act. Explicit anti-pattern: do not pre-plan all searches upfront; each result steers the next.
4. **Parallel tool invocation rule** — "When you need to fetch multiple URLs or run multiple searches, invoke them in a single response with multiple tool calls. Do not chain them sequentially."
5. **Source credibility ladder** — Primary > Secondary > Tertiary > AVOID (SEO content farms). Concrete examples per tier.
6. **Citation rule** — "Every URL you cite must come from a tool call result in this session. Never cite a URL from your training knowledge. If you cite a URL only from `web_search` results (not subsequently `web_fetch`-ed), flag it `unverified` in the Sources table credibility column."
7. **Triangulation rule** — "Every substantive claim must be cross-referenced across ≥2 sources, OR explicitly flagged as single-source in the Findings section."
8. **Stop conditions** — "Stop when: (a) you have enough sources to triangulate the main claims, (b) the last 3 fetches added no new information, (c) you've hit the iteration cap, or (d) the user's defined scope is satisfied. Honest acknowledgement of a gap is better than padding."
9. **Output contract** — fixed file path format, fixed frontmatter, fixed 7 sections. Pattern: `create_file` once at start with the frontmatter + section headings + empty bodies; `edit_file` incrementally as findings arrive; finalise with one last `edit_file` to populate Limitations + Open questions; then `task_report` with the file path.
10. **Anti-patterns to avoid** — (a) don't pad with low-credibility filler; (b) don't keep searching for non-existent sources — admit the gap; (c) don't prefer SEO articles over primary sources; (d) never invent URLs; (e) don't read project code or call integration tools.

### 6.2 PathValidator extension

`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PathValidator.kt:75-112`.

Change signature from `resolveAndValidateForWrite(..., memoryDir: String?)` to `resolveAndValidateForWrite(..., allowedExtraRoots: List<String> = emptyList())`. Update 4 caller tools (`CreateFileTool`, `EditFileTool`, `DeleteFileTool`, `RevertFileTool`) to pass `listOf(memoryDir, researchDir).filterNotNull()`.

New helper `ProjectIdentifier.researchDir()` (parallel to existing `agentDir`) computes the canonical path. Pattern: `{agentDir}/research/`. Created on first write (via `Files.createDirectories`).

Same canonical-path comparison + `..` traversal guard as the existing memory-dir code. No special-case bypass.

### 6.3 ResearchIndex + onResearchFileCreated hook

New file `agent/src/main/kotlin/com/workflow/orchestrator/agent/research/ResearchIndex.kt`. Mirrors `MemoryIndex` (`agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt`) in shape and concurrency discipline:

- `load(researchDir: Path): String?` — reads `RESEARCH.md`, truncates at 200 lines, returns null if missing or empty.
- `onResearchFileCreated(researchDir: Path, createdFile: Path)` — invoked by `CreateFileTool` after a successful write into the research dir. Reads the just-created file's frontmatter (`topic`, `question`) and first non-empty paragraph of the `## Findings` section, derives `{Title}` and `{one-line hook}`, appends `- [{Title}]({filename}) — {hook}` to RESEARCH.md.
- Per-dir `ConcurrentHashMap<Path, Any>` lock — same `indexLocks` pattern as `MemoryIndex`.
- Self-edit guard: `if (createdFile.fileName.toString() == "RESEARCH.md") return` — prevents recursion when the index file itself is created.
- Atomic rewrite: temp file + `Files.move(ATOMIC_MOVE)` — same pattern as `MemoryIndex`.

Filename collision resolution: the Kotlin hook is the authoritative slug generator. The persona is instructed to suggest a slug in its `create_file` `path` arg, but the hook normalises to `YYYY-MM-DD-{slug}-{first6OfSessionId}.md` and silently corrects if the persona omits the session-id suffix. Two same-day sessions on the same topic get distinct files via the session-id discriminator.

### 6.4 SystemPrompt injection

`agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`.

Add a new optional parameter `researchIndex: String? = null` to `SystemPrompt.build(...)`, alongside the existing `memoryIndex` parameter. When non-null and non-empty, emit a new XML block in the prompt:

```
<research_index>
{contents of RESEARCH.md}
</research_index>
```

Position: immediately after the existing `<memory_index>` block in Section 10 (Memory). Header text in the persona-facing prose tells the LLM: "You can read any research file referenced above via `read_file <path>`. Each one is a self-contained markdown report with frontmatter, Sources table, and Findings section."

`AgentService.executeTask()` adds a `ResearchIndex.load(researchDir)` call alongside the existing `MemoryIndex.load(memoryDir)` and passes the result into `SystemPrompt.build(...)`.

Sub-agents inherit the parent's research-index injection only when their config declares `memory: project` (same convention as MEMORY.md). Practically: the `research` persona itself has `memory: project` so it sees the existing index when running — useful to avoid re-researching a topic.

### 6.5 Settings + Settings UI

`core/src/main/kotlin/.../settings/PluginSettings.kt` — add:

```kotlin
var enableResearchSubagent: Boolean = true
```

Project-level. Default `true` matches the house style for `enableWebFetch` / `enableWebSearch` (which both default `true`).

`core/src/main/kotlin/.../settings/ui/AgentAdvancedConfigurable.kt` — add a single `JBCheckBox` labeled "Enable research sub-agent" under a new collapsible "Sub-agents" subsection. Tooltip: "When checked, the LLM can spawn the research sub-agent for thorough external research. Dumps land in ~/.workflow-orchestrator/{proj}/agent/research/."

### 6.6 Settings gate in SpawnAgentTool

`agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt`.

In `executeSingle` (where `agent_type` is resolved to a config), check before dispatch:

```kotlin
if (config.name == "research" && !pluginSettings.enableResearchSubagent) {
    return ToolResult.error("RESEARCH_SUBAGENT_DISABLED: enable it in Tools → Workflow Orchestrator → AI Agent → Sub-agents.")
}
```

Same pattern surfaces for the `/research` slash-command path because the slash command instructs the LLM to call `agent(...)` — the gate fires on that call.

`AgentConfigLoader.filterByIdeContext()` is NOT changed (its signature has no settings access, and module direction is `:agent → :core` which permits the additive check inside `SpawnAgentTool` cleanly).

### 6.7 Slash command — `agent/src/main/resources/skills/research/SKILL.md`

```markdown
---
name: research
description: "Spawn the research sub-agent for thorough external research on a topic. Forwards your topic to a dedicated sub-agent with web_fetch / web_search tools that compiles a sourced markdown report into the project's research folder."
argument-hint: <topic>
---

The user wants thorough external research on the following topic:

$ARGUMENTS

Call the `agent` tool now with these arguments:
- `agent_type`: `research`
- `prompt`: the topic above (verbatim from `$ARGUMENTS`)
- `description`: a 3-5 word summary of the topic

When the sub-agent returns, summarise its dump file path back to the user and offer to `read_file` it if they want the findings inline.
```

This is text the LLM reads — there is no Kotlin-side auto-dispatch. The wiring is honest: the skill teaches the LLM what to do, and the LLM does it.

### 6.8 "Open research folder" toolbar button

`agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt` (the JCEF host for the Agent tab toolbar — confirm exact toolbar wiring during implementation against `AgentCefPanel` peer if the toolbar lives there instead). Add a small button with the IntelliJ "open in file manager" icon. Action: open the OS file browser at `researchDir` (`Desktop.getDesktop().open(researchDir.toFile())` with a `Files.exists()` check + `Files.createDirectories()` fallback if missing).

Tooltip: "Open research folder — browse research dumps".

~5-10 LOC. Replaces the JCEF "Research" tab the original design proposed.

### 6.9 Files-changed table (revised after review)

| Path | Change | Est. LOC |
|---|---|---|
| `agent/src/main/resources/agents/research.md` | NEW persona | +150 |
| `agent/src/main/kotlin/.../tools/builtin/PathValidator.kt` | Signature refactor + research-root allow | +25 / -10 |
| `agent/src/main/kotlin/.../tools/builtin/CreateFileTool.kt` | Pass `allowedExtraRoots` + fire `onResearchFileCreated` hook | +15 |
| `agent/src/main/kotlin/.../tools/builtin/EditFileTool.kt` | Pass `allowedExtraRoots` | +5 |
| `agent/src/main/kotlin/.../tools/builtin/DeleteFileTool.kt` | Pass `allowedExtraRoots` | +5 |
| `agent/src/main/kotlin/.../tools/builtin/RevertFileTool.kt` | Pass `allowedExtraRoots` | +5 |
| `agent/src/main/kotlin/.../ProjectIdentifier.kt` (the helper that owns the existing `agentDir(project)` — locate during implementation; CLAUDE.md "Agent Storage" section confirms the convention) | New `researchDir(project)` helper alongside `agentDir(project)` | +10 |
| `agent/src/main/kotlin/.../research/ResearchIndex.kt` | NEW — load + onResearchFileCreated | +90 |
| `agent/src/main/kotlin/.../prompt/SystemPrompt.kt` | New `researchIndex` parameter + block | +25 |
| `agent/src/main/kotlin/.../AgentService.kt` | Load research index in `executeTask` | +5 |
| `agent/src/main/kotlin/.../tools/builtin/SpawnAgentTool.kt` | Settings gate for `research` persona | +10 |
| `core/src/main/kotlin/.../settings/PluginSettings.kt` | `enableResearchSubagent: Boolean = true` | +3 |
| `core/src/main/kotlin/.../settings/ui/AgentAdvancedConfigurable.kt` | Checkbox + Sub-agents section | +15 |
| `agent/src/main/resources/skills/research/SKILL.md` | NEW slash command | +20 |
| `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` | Toolbar "Open research folder" button | +10 |
| `agent/src/test/kotlin/.../tools/builtin/PathValidatorResearchRootTest.kt` | NEW | +40 |
| `agent/src/test/kotlin/.../research/ResearchIndexTest.kt` | NEW | +50 |
| `agent/src/test/kotlin/.../tools/builtin/SpawnAgentToolResearchGateTest.kt` | NEW | +25 |
| `agent/src/test/kotlin/.../tools/subagent/SubagentSystemPromptSnapshotTest.kt` | EXTEND — 2 new variants | +20 |
| `agent/src/test/resources/subagent-prompt-snapshots/research-null-context.txt` | NEW snapshot | +(generated) |
| `agent/src/test/resources/subagent-prompt-snapshots/research-intellij-ultimate.txt` | NEW snapshot | +(generated) |
| `agent/CLAUDE.md` | Docs update — new persona, research dir, RESEARCH.md injection | +25 |
| `core/CLAUDE.md` | Settings doc update | +3 |

**Total: ~525 LOC across 18 production files + 4 test files.** Bigger than the original ~350 LOC estimate because the review caught that PathValidator + the 4 caller tools need touching, the Kotlin-side hook adds real surface, and snapshot tests need 2 variants not 1.

## 7. Error handling + invariants

1. **PathValidator invariant.** The new `{agentDir}/research/` write root is treated identically to `{agentDir}/memory/` — same canonical-path comparison, same `..` traversal guard. Pinned by `PathValidatorResearchRootTest` with 3+ traversal-attack cases.
2. **Settings off + invocation.** Both `/research` (which the LLM follows by calling `agent(...)`) and direct orchestrator `agent(agent_type="research", ...)` calls hit the same gate in `SpawnAgentTool` and return `RESEARCH_SUBAGENT_DISABLED` with the explicit user-facing message in §6.6. No silent failure.
3. **Empty RESEARCH.md.** `ResearchIndex.load()` returns null. `SystemPrompt.build()` skips the `<research_index>` block entirely (no empty tag, no header line). Behaviour identical to today's empty-MEMORY.md case.
4. **Tool failure mid-research.** If `web_fetch` returns an error, the persona continues with the sources it has and flags the failure in the dump's `## Limitations` section. The persona does NOT abort the session over a single bad fetch.
5. **Filename collision.** Resolved Kotlin-side in `ResearchIndex.onResearchFileCreated` by appending the session-id suffix. The persona is not trusted to pick unique names.
6. **Concurrent research sub-agents.** `ResearchIndex.indexLocks: ConcurrentHashMap<Path, Any>` provides per-dir synchronisation for the RESEARCH.md append. The atomic-rewrite pattern (write `.tmp` + `Files.move(ATOMIC_MOVE)`) ensures readers never see a half-written index.
7. **Citation rule.** Teaching, not enforcement — the persona body instructs the model, but no Kotlin-side post-process scans the dump for hallucinated URLs. The structural bound is: the model can only cite URLs it has seen in tool-call results in its conversation context, so fabrication of fully-novel URLs is implausible. Stale/dead URLs (the arxiv:2604.03173 measured failure) are mitigated by the `unverified` credibility-column flag for `web_search`-only URLs. A future Anthropic-style citation/review verifier sub-agent (§10) would close the last gap.

## 8. Testing

| Test | What it pins |
|---|---|
| `PathValidatorResearchRootTest` | Writes to research dir succeed; writes outside research/memory/project roots are rejected; `..` traversal attempts (`/research/../sessions/x`, `/research/../../etc/passwd`) are rejected. 5 cases minimum. |
| `ResearchIndexTest` | `load()` returns null when RESEARCH.md is missing or empty; passes through when ≤200 lines; truncates at 200 lines when larger. `onResearchFileCreated()` derives title + hook correctly from frontmatter + Findings, appends one line, is idempotent on duplicate calls, no-ops on self-edit. Race test: 5 concurrent appends produce 5 distinct lines under no lock contention. 8+ cases. |
| `SpawnAgentToolResearchGateTest` | `enableResearchSubagent=false` returns `RESEARCH_SUBAGENT_DISABLED` for `agent(agent_type="research", ...)`. Other agent types unaffected. |
| `SubagentSystemPromptSnapshotTest` — `research-null-context.txt` + `research-intellij-ultimate.txt` | Golden snapshots verify the composed sub-agent system prompt (role override + per-section flags + research-specific body) is stable across IDE-context-null and intellij-ultimate variants. Regenerate via the existing `*generate all golden snapshots*` test pattern. |

**Manual smoke (only the user can do):** type `/research <topic>` in chat → research file lands at the expected path → RESEARCH.md gets a new line → next session's system prompt shows the index entry → settings toggle disables both paths → "Open research folder" button opens the OS file browser at the right dir.

## 9. Documentation updates

In the same commit as the implementation:

- **`agent/CLAUDE.md`** — add `research` to the bundled-agents list ("Bundled specialist agents"); add a new "Research dir + RESEARCH.md" subsection under "Storage Tiers" (tier 3); update the "File-Based Memory System" section to note that the same auto-injection pattern now also applies to research.
- **`core/CLAUDE.md`** — add `enableResearchSubagent: Boolean` to the project-level settings list.
- **`docs/architecture/index.html`** — note the new persona + dir in the architecture summary.
- This spec (`docs/superpowers/specs/2026-05-24-research-subagent-design.md`) — already authoritative.
- The survey (`docs/research/2026-05-24-research-subagent-persona-survey.md`) — already authoritative.

Per the user's `feedback_update_docs_immediately.md`: docs land in the same commit as the architecture change.

## 10. Deferred to v2

| Item | Why deferred |
|---|---|
| JCEF "Research" tool-window tab | Reviewer's case: no proven adoption yet; 6 tabs already; memory has no dedicated tab (browse via `read_file`). "Open research folder" button covers v1 needs at ~5 LOC instead of ~105. Revisit when telemetry shows the persona is used heavily. |
| Anthropic-style citation/review verifier sub-agent | The teaching-not-enforcement citation rule is structurally bounded for hallucination; the `unverified` flag covers staleness. A separate verifier sub-agent that re-fetches each cited URL would close the last gap but isn't justified by v1 evidence. |
| Fan-out within the research persona | The orchestrator already provides fan-out via `agent(prompt_2=..., prompt_3=...)`. Adding a second layer (research sub-agent spawning child research sub-agents) is unnecessary complexity unless we hit the 200-iteration cap on a single-topic research session. |
| RESEARCH.md compaction past 200 lines | Today: hard truncate to first 200. Later: relevance-sorted compaction (similar to MEMORY.md if/when MEMORY.md gets that treatment). Track separately. |
| Auto-dedup when persona produces a duplicate-topic dump | Today: appends another file with a distinct session-id suffix. Later: warn-and-replace. Track separately. |

## 11. Open questions / assumptions baked in

1. **Slug generation.** Assumed: the LLM suggests a slug, Kotlin normalises and appends session-id. Alternative considered (and rejected): Kotlin derives slug from frontmatter `topic` field. Rejected because the persona's `create_file` call happens BEFORE all findings are in — the topic in frontmatter may be a draft. Letting the LLM propose feels more honest.
2. **`read_file` scope for the research persona.** The persona has `read_file` in its tools but no PathValidator restriction on reads (today's `PathValidator.resolveAndValidateForRead` allows the whole project + agent dir). The persona body explicitly tells it not to read project code, but this is enforced by prompt only. Tighter enforcement (a per-persona read-root allowlist) is deferred — too invasive for v1.
3. **Sub-agent receives research-index injection?** Assumed yes when `memory: project` (the research persona has it). Alternative: never inject research-index into sub-agents to keep their context clean. Picked yes because a research sub-agent benefits from knowing what's already been researched (avoid duplication).

## 12. References

- Industry survey: [`docs/research/2026-05-24-research-subagent-persona-survey.md`](../../research/2026-05-24-research-subagent-persona-survey.md).
- Existing sub-agent architecture: `agent/CLAUDE.md` — sections "Agent Tool (Subagent Management)", "Custom Subagents", "Unified Sub-agent Prompt Pipeline", "File-Based Memory System".
- Existing bundled personas (style reference): `agent/src/main/resources/agents/explorer.md`, `security-auditor.md`.
- Existing PathValidator: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/PathValidator.kt`.
- Existing MemoryIndex (pattern reference for ResearchIndex): `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt`.
- Review (Opus) findings folded into this spec: 2 BLOCKER + 4 IMPORTANT + 4 NIT; reconciliation table in conversation history of the brainstorming session.

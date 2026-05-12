# System Prompt — Claim/Code Mismatch Audit (2026-05-12)

## Disposition status (as of 2026-05-12)

| Finding | Severity | Status |
|---|---|---|
| H1 — `run_command` approval semantics | HIGH | ✅ FIXED |
| H2 — `send_stdin` misdirected for bg_ IDs | HIGH | ✅ FIXED |
| H3 — `heads_up` requires `discovery` | HIGH | ✅ FIXED |
| H4 — PSI tools listed as "always available" | HIGH | ✅ FIXED |
| H5 — "parallel `edit_file` fine" | HIGH | ✅ FIXED |
| (bonus) Read-only parallelism preference | — | ✅ ADDED |
| M3 — `run_command` 300s vs 600s timeout drift | MEDIUM | ✅ FIXED |
| M6 — `<thinking>` rendered live to user | MEDIUM | ✅ FIXED |
| M7 — Missing `ai_review` / `get_build_problems` from inventory | MEDIUM | ✅ FIXED |
| M1 — `enable_plan_mode` absent from execution-guard `WRITE_TOOLS` | MEDIUM | ⏸ SKIP — schema filter already hides it; call is a no-op anyway |
| M2 — `discard_plan` filtered in act mode | MEDIUM | ⏸ SKIP — only relevant in plan mode where the prompt wording applies |
| M4 — `environment_details` "each message" overstated | MEDIUM | ⏸ SKIP — present on all turns that semantically need it |
| M5 — "parallel only for explorer" overstates | MEDIUM | ⏸ SKIP — only affects custom agents; bundled name is correct |
| M8 — `addBlocks` parameter undocumented | MEDIUM | ⏸ SKIP — same DAG edge expressible via `addBlockedBy` on inverse task |
| L1 — diff context line-count drift | LOW | ⏸ SKIP — invisible to LLM behaviour |
| L2 — React Flow scope omits 6 hooks | LOW | ⏸ SKIP — self-repair loop catches; only a token-cost optimisation |
| L3 — `output_file` vs auto-spill conflation | LOW | ⏸ SKIP — existing wording isn't wrong, just under-explained |
| L4 — `task_list` omits `blocks` edges | LOW | ⏸ SKIP — `task_get` is one call away |
| L5 — `UseSkillTool` doc string stale | LOW | ⏸ SKIP — dev-only, not LLM-visible |

**Decision rule applied (per user's "if required, fix; if not, don't"):** required = LLM produces demonstrably wrong output, or user sees unexpected behaviour. The 8 skipped findings are docs/code consistency issues with no behavioural impact.

## Context

Triggered by a real-world bug: the LLM leaked `<tool>` / `<tool_name>` XML into visible
chat content. Root cause was a literal `<tool_name>` placeholder in
`ToolPromptBuilder.FORMAT_INSTRUCTIONS` that the model echoed verbatim (since `tool_name`
is not a registered tool, `AssistantMessageParser` passed it through as `TextContent`).

That fix shipped — `ToolPromptBuilder` now teaches by concrete example, and
`AssistantMessageParser.stripLeakedToolXml` plus `hasUnclosedLeakedTag` provide a
defense-in-depth filter in `AgentLoop`'s streaming layer.

This audit looks for **other** discrepancies of the same shape: places where
`SystemPrompt.kt` makes a concrete behavioral claim that the actual code does not
support, supports differently, or supports only partially.

## Method

Four parallel sonnet subagents read `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt`
section by section and verified each concrete claim against the implementation
(`AgentLoop`, `AgentService`, `ApprovalPolicy`, individual tool classes, etc.).

Classification:
- **FALSE** — claim contradicts code
- **HALF-TRUTH** — partially true / true only in some configs / glosses over a caveat
- **STALE** — refers to something renamed/removed
- **PLACEHOLDER ECHO** — same shape as the original `<tool_name>` bug

Two findings were spot-verified against source after the audit (marked ✅ verified).
The rest are subagent-reported and should be confirmed before fix.

---

## Consolidated Findings — Severity Ordered

### HIGH — user-visible misbehavior or runtime errors

#### H1 — `run_command` approval semantics are materially wrong  [FALSE]  ✅ verified
- **Prompt says (rules section, line 650):** *"Read-only commands auto-approve. Mutating remote commands need approval. localhost curl/wget always allowed."*
- **Reality:** `ApprovalPolicy.kt:16` puts `run_command` in `ALWAYS_PER_INVOCATION` → `requiresApproval=true, allowSessionApproval=false` for **every** invocation regardless of `CommandSafetyAnalyzer` risk classification. The risk label only changes the approval card's color, not whether it appears.
- **Impact:** LLM may plan around assumed auto-approve fast-paths; UX expectations misaligned.
- **Fix:** Rewrite to: *"All `run_command` calls show an approval card — SAFE-classified commands (read-only, localhost curl/wget) show a low-risk label but still require a click."*

#### H2 — `send_stdin` misdirected to bare tool for background processes  [FALSE]
- **Prompt says (capabilities, line 377):** *"…use `background_process(action=kill)` to terminate and `send_stdin` to feed input to a still-running process"*
- **Reality:** Bare `send_stdin` only handles synchronous `ProcessRegistry` IDs. Background process IDs (`bg_*`) go through `background_process(action=send_stdin)`. The correct guidance appears four lines later but the misdirection at 377 is read first.
- **Fix:** Replace bare `send_stdin` with `background_process(action=send_stdin)` in the kill-and-feed sentence; reserve standalone `send_stdin` mention for the foreground-pause case.

#### H3 — `attempt_completion(kind='heads_up')` runtime requires `discovery` but prompt never says so  [HALF-TRUTH]  ✅ verified
- **Prompt says (objective, line 792):** *"Choose `kind='done'` when complete, `kind='review'` when the user must inspect something, `kind='heads_up'` when you found something surprising…"*
- **Reality:** `AttemptCompletionTool.kt:51` documents `discovery` as required when `kind=heads_up`; the tool's own `llmMistake()` block at line 75 says this is *"a recurring pattern"* — model picks `heads_up`, forgets `discovery`, gets rejected.
- **Fix:** Append: *"Always supply `result` (required); for `kind='heads_up'`, also supply `discovery` (required — omitting it causes a runtime error)."*

#### H4 — PSI core tools listed as "always available" but IDE-gated  [FALSE]
- **Prompt says (capabilities, line 296):** *"Code intelligence: find_definition, find_references, diagnostics"* under heading *"Core tools (always available):"*
- **Reality:** `AgentService.kt:889-897` guards all three behind `hasPsiSupport` (`hasJavaPlugin || supportsPython`). WebStorm / bare-IDE users have none of them registered.
- **Fix:** Move PSI line into a conditional block: *"Code intelligence (when Java or Python plugin present)…"* — mirror existing conditional pattern for runtime tools.

#### H5 — "Parallel `edit_file` calls are fine" contradicts sequential write execution  [FALSE]
- **Prompt says (editingFiles, line 239):** *"For multiple independent edits in the same file, make multiple edit_file calls (parallel calls are fine when edits don't overlap)."*
- **Reality:** `:agent` CLAUDE.md and `AgentLoop` execution model: write tools always execute sequentially (`coroutineScope { async { } }` only fans out read-only tools).
- **Fix:** Drop the parenthetical; clarify edits are serialized regardless of LLM intent.

### MEDIUM — correctness drift, LLM may follow stale or under-specified guidance

#### M1 — Defense-in-depth gap: `enable_plan_mode` schema-filtered but absent from `WRITE_TOOLS` execution guard  [HALF-TRUTH]
- **Prompt says (actVsPlanMode, line 259):** lists `enable_plan_mode` among write tools blocked in plan mode.
- **Reality:** `AgentService.kt:1705` filters it from schema, but `AgentLoop.kt` `WRITE_TOOLS` set does not contain it — execution guard would not catch a cached/replayed call.
- **Fix:** Either add `"enable_plan_mode"` to `WRITE_TOOLS`, or rephrase prompt to distinguish schema-removal from execution-guard.

#### M2 — `discard_plan` filtered in act mode but prompt implies always available  [STALE]
- **Prompt says (actVsPlanMode, lines 257 + 277):** *"ACT MODE has all tools EXCEPT plan_mode_respond"* and *"You CAN call discard_plan to clear a plan you have already presented"* (no qualifier).
- **Reality:** `AgentService.kt:1707` also strips `discard_plan` in act mode.
- **Fix:** Change line 257 to *"EXCEPT plan_mode_respond and discard_plan"*; qualify line 277 to plan-mode only.

#### M3 — `run_command` 600s timeout claim drifts from 300s default  [HALF-TRUTH]
- **Prompt says (rules, line 692):** *"120s default; 600s for run_command…"*
- **Reality:** Outer `AgentLoop` wrapper is 600s (`LONG_TOOL_TIMEOUT_MS`), but `RunCommandTool` has its own `DEFAULT_TIMEOUT_SECONDS=300L` applied per-execution. Commands die at 300s unless LLM passes `timeout=N`.
- **Fix:** *"600s outer wrapper for `run_command` (command `timeout` param defaults to 300s; pass `timeout=N` to extend up to the user-configured ceiling, default 600s max)."*

#### M4 — `environment_details` injection frequency overstated  [HALF-TRUTH]
- **Prompt says (rules, line 651):** *"environment_details is appended to each message automatically"*
- **Reality:** Only appended to initial-task, steering, and `new_task` handoff messages (5 call sites in `AgentLoop.kt`). Not on nudges, empty-response retries, tool results.
- **Fix:** *"environment_details is appended to each new user task message and steering message — not to tool results or internal nudges."*

#### M5 — "Parallel only for explorer" overstates the restriction  [HALF-TRUTH]
- **Prompt says (rules, line 742):** *"Parallel execution is only available for read-only agents (explorer)."*
- **Reality:** `SpawnAgentTool.inferPlanMode()` gates on *"no write tools in resolved tool set"*, not on the literal agent type name. Any custom agent with no write tools qualifies.
- **Fix:** *"Parallel execution is available for any agent with no write tools in its resolved tool set. `explorer` is the primary built-in example; custom read-only agents also qualify."*

#### M6 — `<thinking>` tags rendered live to user, not silent scratchpad  [HALF-TRUTH]
- **Prompt says (objective, line 791):** *"Before calling a tool, think within `<thinking></thinking>` tags: which tool is most relevant?"*
- **Reality:** `ThinkingTagSplitter` routes thinking-tag content as `Part.ThinkingDelta` events into `dashboard.appendToThinking()` — rendered live in a collapsible "Reasoning" block in the chat UI.
- **Fix:** Add: *"Content inside `<thinking>` is displayed to the user as a live reasoning block — keep it legible and on-topic."*

#### M7 — Missing core tools from inventory: `ai_review`, `get_build_problems`  [HALF-TRUTH]
- **Prompt says (capabilities, lines 293-303):** lists "Core tools (always available)."
- **Reality:** `AgentService.kt` registers `ai_review` (PR review staging) and `get_build_problems` (local build/import errors) unconditionally, but they don't appear in the prompt inventory. LLM defaults to `sonar` or shell-build commands instead.
- **Fix:** Add one-phrase entries for both.

#### M8 — `task_update` mentions only `addBlockedBy`, omits `addBlocks`  [HALF-TRUTH]
- **Prompt says (taskProgress, line 218):** *"Use `addBlockedBy` on `task_update` to express 'this task can't start until X and Y complete.'"*
- **Reality:** `TaskUpdateTool.kt:47-51` exposes both `addBlockedBy` and `addBlocks` (outgoing edges). Prompt gives no signal that the reverse direction exists.
- **Fix:** Extend to mention both directions.

### LOW — minor drift / dev-only

#### L1 — `editingFiles` diff context "≈3 lines before/after" off by one  [HALF-TRUTH]
- **Prompt says (line 242):** *"diff context (≈3 lines before/after the edit)"*
- **Reality:** `EditFileTool.kt:253-263` — 4 lines before, 3 lines after.
- **Fix:** Numeric correction. Trivial.

#### L2 — `render_artifact` React Flow scope omits 6 registered hooks  [HALF-TRUTH]
- **Prompt says (capabilities, line 373):** lists `ReactFlowCanvas, Background, Controls, MiniMap, Handle, Position, MarkerType, useNodesState, useEdgesState`
- **Reality:** `RenderArtifactTool.kt:468-470` also exposes `useReactFlow, addEdge, applyNodeChanges, applyEdgeChanges, ReactFlowProvider`, plus the `ReactFlow` namespace.
- **Fix:** Append the six missing identifiers — or cross-reference `SCOPE_HINT` directly.

#### L3 — `output_file=true` vs auto-spill semantics conflated  [HALF-TRUTH]
- **Prompt says (line 362):** *"use `grep_pattern` for line filtering, or `output_file=true` + read_file if you need the dropped head"*
- **Reality:** `ToolOutputSpiller` auto-spills **any** output > 30K to disk regardless of the flag. `output_file=true` opts into explicit disk save for smaller outputs.
- **Fix:** Clarify that the spill is automatic and the path is already in the truncation footer.

#### L4 — `task_list` doesn't include `blocks` edges, no warning in prompt  [STALE]
- **Prompt says (taskProgress, line 226):** *"task_list returns minimal fields (id, subject, status, owner, blockedBy) — cheap to call often."*
- **Reality:** Correct on what *is* included; doesn't warn that `blocks` (outgoing) is silently absent — LLM reasoning about the DAG may get an incomplete picture.
- **Fix:** Add a note pointing at `task_get` for outgoing edges.

#### L5 — `UseSkillTool.technical()` doc says `.workflow/skills/` — should be `.agent-skills/`  [STALE — dev-only]
- **Prompt says (via injected `using-skills` SKILL.md):** correct (`.agent-skills/`)
- **Reality:** `UseSkillTool.kt:59` doc string is stale. Not LLM-visible directly, but may be served via `tool_search` and is misleading to developers.
- **Fix:** One-line edit in `UseSkillTool` `technical()`.

---

## Suggested batching

For PR ergonomics:

- **PR-A (high-impact prompt fixes):** H1, H2, H3, H4, H5 — single commit to `SystemPrompt.kt`; no code changes; high signal-to-noise. Update snapshot tests.
- **PR-B (defense-in-depth code fix):** M1 — add `enable_plan_mode` to `WRITE_TOOLS`; one-line change + test.
- **PR-C (medium prompt drift):** M2, M3, M4, M5, M6, M7, M8 — prompt-only batch.
- **PR-D (low-priority polish):** L1, L2, L3, L4, L5 — bundle.

Each PR should regenerate the 7 prompt snapshot tests if the system prompt diff lands.

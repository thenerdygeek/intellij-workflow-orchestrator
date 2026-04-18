# Plan: `attempt_completion` Tool Redesign

**Date:** 2026-04-18
**Author:** Subhankar (planning session with assistant)
**Status:** Ready for execution
**Branch / worktree:** `feature/tooling-architecture-enhancements` (current worktree — no new branch, no switch)
**Scope:** Tool contract, loop-exit semantics, persistence shape, UI rendering, system-prompt teaching

---

## 1. Motivation

Two problems with the current `attempt_completion` tool surfaced during review:

1. **Live-path bug around `command`.** `chatStore.addCompletionSummary` JSON-encodes `{result, verifyCommand}` into `msg.text`, but `ChatView.tsx:481` passes `msg.text` straight to `<CompletionCard>` as `result` and never extracts `verifyCommand`. When the LLM supplies a `command`, users see a raw JSON blob in the summary body instead of a proper verify pill below it. The resume path is worse — `AgentLoop.kt:1411-1416` only persists `text.take(2000)` and drops `verifyCommand` entirely.

2. **One-size-fits-all card.** Every completion renders as the same green "Task Completed" card regardless of whether the task succeeded cleanly, needs user verification, or surfaced a surprising finding. Users have no information scent to triage completed sessions, and the LLM has no discipline forcing it to classify its own output.

Fixing (1) in isolation is a two-line patch, but doesn't address (2). This plan treats both together: the tool contract, persistence shape, and card UI are redesigned as one coherent change.

---

## 2. Locked Design Decisions

These were agreed in the 2026-04-18 planning discussion and are not open for re-litigation in this plan. Each links back to the reasoning:

| # | Decision |
|---|---|
| 1 | **Enum axis:** "user action expected" (single axis, no mixing with "work type" or "confidence"). |
| 2 | **One-hot:** exactly one `kind` per call. No multi-label. |
| 3 | **Flat schema (option A):** single `attempt_completion` tool with `kind` enum + conditionally-required optional fields. Not 3 separate tools. Cody-friendly (no `oneOf`/`anyOf` variant schemas). |
| 4 | **Three kinds:** `done` / `review` / `heads_up`. `partial` dropped (slack-off risk). `blocked` dropped (overlaps with `ask_followup_question`). |
| 5 | **`task_report` unchanged.** Sub-agent → parent-LLM structured handoff keeps its existing schema. `kind` is exclusively for `attempt_completion` because the user-action-expected axis only matters when a human is the consumer. |
| 6 | **Invariant A — tool is the exit signal only.** `attempt_completion` exists solely to stop iteration. Description and system prompt must reflect "stop iteration" framing, not "present result" framing. |
| 7 | **Invariant B — stuck mid-task routes to `ask_followup_question`.** If the LLM can't proceed and user input can unblock, use `ask_followup_question`. If the task produced substantial work (even if not 100%), call `attempt_completion(done)` with an honest narrative in `result`. No `partial` escape hatch. |
| 8 | **Persistence: typed side-car.** New `completionData: CompletionData?` field on `UiMessage`, matching the existing pattern of `toolCallData` and `approvalData`. No JSON-in-text. |
| 9 | **UI: one `CompletionCard`, kind discriminator.** Shared chrome (border, header, copy-all, animation). Accent colour, icon, label, body-layout switch on `kind`. Not three separate components. |
| 10 | **Teaching: two layers.** (a) tool description with per-kind criteria + one example each; (b) system-prompt "Completion kinds" subsection in rules. Third layer (`<thinking>` self-classification) dropped — Sourcegraph Cody hides thinking output, so unobservable. |
| 11 | **`verify_how` is optional across all kinds.** Renamed from current `command` param; broader meaning (CLI command / URL / "open file X" / any verification instruction). `kind` = urgency, `verify_how` = mechanism — orthogonal. |
| 12 | **Interactive affordances deferred.** No per-kind buttons in v1 (no "Continue this task", no "Save discovery to memory"). Ship pure visual variants first, add affordances later based on telemetry. |

### Final field list

```kotlin
attempt_completion(
  kind:       "done" | "review" | "heads_up"   // required
  result:     string                            // required — narrative of what happened
  verify_how: string?                           // optional on all kinds
  discovery:  string?                           // REQUIRED when kind=heads_up; else null/omitted
)
```

### Per-kind UI treatment (locked baseline)

| Kind | Accent | Icon | Header label | Body layout |
|---|---|---|---|---|
| `done` | `--success` (green) | ✓ filled circle | "Task Completed" | `result` as markdown; `verify_how` pill below body if present |
| `review` | `--warning` (amber) | 👁 / 📋 (TBD in exec) | "Please Review" | `result` as markdown; `verify_how` pill **above body**, prominent — it is the CTA |
| `heads_up` | `--info` (blue) | ⚡ / ℹ️ (TBD in exec) | "Heads Up" | `result` on top; `discovery` as a quoted callout block inside the card (visually distinct from body prose); `verify_how` pill below if present |

---

## 3. Architectural Summary

```
         LLM
          │
          ▼  attempt_completion(kind, result, verify_how?, discovery?)
┌────────────────────────┐
│ AttemptCompletionTool  │  validates per-kind field requirements
└────────────┬───────────┘
             │ returns ToolResult.completion(
             │   completionData = CompletionData(kind, result, verify_how, discovery))
             ▼
┌────────────────────────┐
│ AgentLoop.run()        │  detects isCompletion, persists as
│                        │  UiMessage(ask=COMPLETION_RESULT, completionData=...)
│                        │  returns LoopResult.Completed(completionData)
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│ AgentController        │  onCompletion → dashboard.appendCompletionCard(completionData)
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│ AgentCefPanel          │  JCEF bridge: window._appendCompletionCard(payload)
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│ jcef-bridge.ts         │  chatStore.addCompletionCard(data)
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│ chatStore              │  UiMessage{ask:'COMPLETION_RESULT', completionData}
│                        │  — NO JSON-in-text encoding
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│ ChatView               │  switch on msg.completionData → <CompletionCard data={...}/>
└────────────┬───────────┘
             ▼
┌────────────────────────┐
│ CompletionCard         │  switch(kind) → done / review / heads_up layout branch
└────────────────────────┘
```

---

## 4. Out of Scope

Explicitly **not** in this plan:

- Any change to `task_report` (sub-agent completion tool).
- Any change to `ask_followup_question`, `plan_mode_respond`, or other communication tools.
- Interactive affordances per kind (Continue/Save/Mark-reviewed buttons) — deferred.
- Session history panel (`HistoryView`) card-preview updates — can happen opportunistically but not required.
- Any change to approval gate, hook system, or agent loop cancellation semantics.
- Migration script for old `ui_messages.json` — backward compat is handled by fallback render (see §8), no rewrites.
- `task_budget` / `max_budget_usd` / `rewind_files` ideas from the claude-agent-sdk-python analysis — separate plan.

---

## 5. File Inventory

Files modified or created, grouped by layer. References are to the `feature/tooling-architecture-enhancements` worktree.

### 5.1 Kotlin — Tool contract & data types
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionTool.kt` — **rewrite** schema + execute
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/AgentTool.kt` — modify `ToolResult`: replace `verifyCommand: String?` with `completionData: CompletionData?`; update `ToolResult.completion()` factory
- **NEW:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/CompletionData.kt` — new data class + `CompletionKind` enum

### 5.2 Kotlin — Loop & persistence
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/LoopResult.kt` — replace `verifyCommand` with `completionData` on `Completed` variant
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` — lines 1411-1416, 1506-1513, 1519: persist `completionData` on `UiMessage`, propagate through `LoopResult`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/UiMessage.kt` — add `completionData: CompletionData? = null` field; keep `text` for backward compat

### 5.3 Kotlin — Controller & bridge
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` — line 1518: switch to `dashboard.appendCompletionCard(completionData)`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt` — lines 508-511: rename/retype `appendCompletionSummary` → `appendCompletionCard(data: CompletionData)`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt` — lines 676-677: serialise `CompletionData` to JSON, invoke new bridge function

### 5.4 Kotlin — System prompt
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` — lines 196, 230-232, 384, 406, 500, 607: reframe completion mentions under invariant A + B; add "Completion kinds" subsection

### 5.5 Kotlin — Sub-agent adjacency (sanity check only, no changes expected)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt` — verify `COMPLETING_YOUR_TASK_SECTION` still works (sub-agents use `task_report`, not `attempt_completion`)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt` — line 359 (`verifyCommand = null`) may need to become `completionData = null` for type consistency

### 5.6 Webview (TypeScript/React)
- `agent/webview/src/bridge/types.ts` (or wherever `UiMessage` TS type lives) — add `completionData?: CompletionData`, add `CompletionData` type + `CompletionKind` union
- `agent/webview/src/bridge/jcef-bridge.ts` — line 91-92: replace `appendCompletionSummary` with `appendCompletionCard(data: CompletionData)`
- `agent/webview/src/stores/chatStore.ts` — lines 172, 617-628: rewrite `addCompletionSummary` → `addCompletionCard(data)`; **delete JSON-in-text hack**
- `agent/webview/src/stores/chatStore.ts` — lines 1520-1545: update legacy TOOL-message upgrade path; old `attempt_completion` messages without `completionData` fall back to `done` kind with `text` as `result`
- `agent/webview/src/components/agent/CompletionCard.tsx` — **rewrite** to accept `data: CompletionData` prop; `switch(data.kind)` on body layout; per-kind accent/icon/label
- `agent/webview/src/components/chat/ChatView.tsx` — lines 477-484: pass `msg.completionData` (or synthesise fallback from `msg.text`) to `<CompletionCard>`
- `agent/webview/src/showcase.tsx` — lines 246, 661, 860: update to use new prop shape for showcase rendering

### 5.7 Tests
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/AttemptCompletionToolTest.kt` — update existing test; add per-kind tests
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ToolResultTest.kt` — update for new `completionData` field (drop `verifyCommand`)
- **NEW:** Kotlin test for `CompletionData` JSON serialisation / deserialisation
- **NEW:** resume-path integration test — write a `ui_messages.json` with `completionData`, load, assert `ChatView` renders correct kind
- `agent/src/test/resources/prompt-snapshots/*.txt` — **regenerate all 7 snapshots** after `SystemPrompt.kt` changes
- `agent/webview/src/__tests__/copy-button-clipboard.test.tsx` — line 136: update to new prop shape
- **NEW:** React test for `CompletionCard` per-kind rendering (at minimum: one test per kind confirming correct accent + label + body layout)

---

## 6. Implementation Phases

Phases are designed to be independently verifiable. Phases 1–3 can be landed as a single commit or separately. Phases 4–5 are additive.

### Phase 1 — Kotlin contract + data model

**Goal:** The tool accepts the new schema and returns structured completion data end-to-end in Kotlin, with no UI changes yet. Verifiable via unit tests and tool-harness testing.

Tasks:
1.1. Create `CompletionData.kt` with `CompletionKind` enum (`DONE`, `REVIEW`, `HEADS_UP`) and `CompletionData(kind, result, verifyHow?, discovery?)` data class. Use `@Serializable` (kotlinx-serialization) so it flows cleanly to JSON for persistence and for the JCEF bridge.
1.2. Add `completionData: CompletionData? = null` to `ToolResult`. Remove `verifyCommand: String?` (delete, don't deprecate — internal API). Update `ToolResult.completion()` factory to accept `CompletionData` instead of `verifyCommand`.
1.3. Rewrite `AttemptCompletionTool.kt`: new `parameters` schema with `kind` enum + conditionally-required fields; `execute()` validates per-kind requirements (e.g., `kind=heads_up` without `discovery` → error `ToolResult`); returns `ToolResult.completion(completionData = CompletionData(...))`.
1.4. Update `LoopResult.Completed` to carry `completionData: CompletionData?` (replacing `verifyCommand`).
1.5. Update `AgentLoop.kt` three touch sites (1411-1416, 1506-1513, 1519) to persist full `CompletionData` and propagate through `LoopResult`.
1.6. Add `completionData: CompletionData? = null` to `UiMessage` data class.
1.7. Update `AttemptCompletionToolTest.kt` + `ToolResultTest.kt` for new schema. Cover: success cases for each kind; validation failure when `heads_up` missing `discovery`; round-trip JSON serialisation of `CompletionData`.

**Acceptance:**
- `./gradlew :agent:test --tests "*AttemptCompletion*" --tests "*ToolResult*" --tests "*CompletionData*"` all green.
- Tool-harness invocation of the tool with each kind produces structurally-correct `ToolResult.completionData`.

### Phase 2 — Kotlin controller & JCEF bridge

**Goal:** Structured `CompletionData` traverses the Kotlin UI pipeline intact and crosses the JCEF boundary as JSON. Still no webview changes.

Tasks:
2.1. Rename `AgentDashboardPanel.appendCompletionSummary` → `appendCompletionCard(data: CompletionData)`. Forward to `cefPanel?.appendCompletionCard(data)` + broadcast.
2.2. Rewrite `AgentCefPanel.appendCompletionCard`: serialise `CompletionData` to JSON via `Json.encodeToString`, invoke new JS function `window._appendCompletionCard(payload)` using existing `JsEscape` / `callJs` pattern. Drop `JsEscape.toJsString(verifyCommand)` — replaced by structured JSON.
2.3. Update `AgentController.kt:1518` call site to pass `result.completionData` (the new `LoopResult.Completed.completionData`) to `dashboard.appendCompletionCard`.
2.4. Update `SpawnAgentTool.kt:359` from `verifyCommand = null` to `completionData = null` in its synthesised `ToolResult`.

**Acceptance:**
- Kotlin compiles with no references to `verifyCommand` anywhere.
- Manual smoke: trigger a completion in `runIde`, observe Kotlin logs / debug panel show `CompletionData` flowing to bridge.

### Phase 3 — Webview / React rewrite

**Goal:** Card renders correctly per kind; JSON-in-text hack gone; backward compat preserved.

Tasks:
3.1. Add `CompletionKind` + `CompletionData` TS types in `bridge/types.ts`; extend `UiMessage` with `completionData?: CompletionData`.
3.2. Update `bridge/globals.d.ts` for the new injected `window._appendCompletionCard`.
3.3. Rewrite `jcef-bridge.ts:91-92`: new `appendCompletionCard(data: CompletionData)` function that calls `chatStore.addCompletionCard(data)`.
3.4. Rewrite `chatStore.ts:617-628` → `addCompletionCard(data: CompletionData)`: pushes a `UiMessage` with `type: 'ASK', ask: 'COMPLETION_RESULT', completionData: data` and **no text payload**.
3.5. Update legacy-upgrade path (`chatStore.ts:1520+`) for old TOOL-message → COMPLETION_RESULT migration: synthesise `completionData: { kind: 'done', result: parsed?.result ?? text }` so old sessions open with a `done` card.
3.6. Rewrite `CompletionCard.tsx` to accept `data: CompletionData` prop. Shared chrome (border, header, copy-all, animation) stays uniform. `switch(data.kind)`:
   - `done`: green accent, ✓ icon, "Task Completed" label, `result` markdown body, `verify_how` pill below if present.
   - `review`: amber accent, eye/clipboard icon, "Please Review" label, `verify_how` pill **above** body if present, `result` markdown below.
   - `heads_up`: blue accent, bolt/info icon, "Heads Up" label, `result` markdown, `discovery` as styled quoted callout block, `verify_how` pill below if present.
3.7. Update `ChatView.tsx:477-484`: if `msg.completionData` is present, render `<CompletionCard data={msg.completionData} />`; else fallback (old sessions) render `<CompletionCard data={{kind:'done', result: msg.text ?? ''}} />`.
3.8. Update `showcase.tsx` three call sites to new prop shape — one showcase per kind so developers can eyeball all three in the dev harness.
3.9. Update `copy-button-clipboard.test.tsx` test to new prop shape.
3.10. Add React Testing Library tests — `CompletionCard.test.tsx` with three tests (one per kind) asserting correct accent, label, and body layout.

**Acceptance:**
- `npm run build` clean.
- Manual smoke in `runIde`: invoke a task that leads to each kind; visually verify each card's appearance.
- Old session (one with legacy `text`-only COMPLETION_RESULT in `ui_messages.json`) opens cleanly, renders as `done` kind fallback.
- `chatStore.ts` has zero references to `JSON.stringify({result, verifyCommand})` — the bug is gone by construction.

### Phase 4 — System-prompt teaching

**Goal:** LLM picks the correct `kind` consistently. Snapshot tests regenerated.

Tasks:
4.1. Rewrite `AttemptCompletionTool.description` under invariant A: lead with "Call this tool to stop iteration. This is the only way to end a task." Follow with per-kind criteria and one example result per kind. Remove old "confirm from user that previous tool uses were successful" language (invariant A supersedes it).
4.2. Update `SystemPrompt.kt` line 196 (ACT MODE): reframe to "To end a task, call `attempt_completion` with the appropriate `kind`. Text-only responses and silent exits are not valid."
4.3. Update `SystemPrompt.kt` lines 384, 406 (Next Steps / no-tool-call nudges): replace "If you have completed the user's task" with "To end a task for any reason — including work you could not fully complete — use `attempt_completion`. If you need more information, use `ask_followup_question`."
4.4. Update `SystemPrompt.kt` line 500 (Rules / Communication): rewrite the "attempt_completion result is a SHORT summary card" line to cover all three kinds with a specific guideline for each.
4.5. Update `SystemPrompt.kt` line 607 (verification rule): relax "confirm output files exist" from mandatory to "When applicable, verify that your changes took effect before calling `attempt_completion`." Not all kinds require file verification (e.g., `heads_up` on a read-only analysis).
4.6. Add a new "Completion kinds" subsection in Rules / Communication (between lines 500 and 503): one-sentence semantics per kind + when to prefer `ask_followup_question` over `attempt_completion`.
4.7. Regenerate all 7 prompt snapshots: `./gradlew :agent:test --tests "*generate all golden snapshots*"`. Review the diffs carefully; commit snapshots alongside the code changes.

**Acceptance:**
- `./gradlew :agent:test --tests "*SNAPSHOT*"` green.
- Diff review shows intentional wording changes only; no accidental drift.

### Phase 5 — Observability

**Goal:** We can see the LLM's kind-distribution in telemetry after ship.

Tasks:
5.1. Add `completionKindCounts: MutableMap<CompletionKind, Int>` to `SessionMetrics`. Increment in `AgentLoop` when persisting a completion.
5.2. Include kind in the JSONL log entry emitted by `AgentFileLogger` at loop-exit time (alongside iteration count, token totals).
5.3. (Optional, one-line) Add a small line to the end-of-session summary (if one exists in debug log) showing "Exited via attempt_completion(kind=X)".

**Acceptance:**
- Run a few sessions manually, inspect `~/.workflow-orchestrator/{proj}/logs/agent-YYYY-MM-DD.jsonl`, confirm kind is captured.
- `SessionMetrics` test (existing) extended to verify kind increment logic.

---

## 7. Testing Strategy

| Layer | What we test | How |
|---|---|---|
| Tool contract | Schema validation, per-kind field requirements, error on missing required fields | `AttemptCompletionToolTest.kt` — one test per kind + two negative tests |
| `ToolResult` / `LoopResult` | `completionData` propagates through factories + pattern matching | `ToolResultTest.kt` |
| Data class | JSON round-trip (kotlinx-serialization) | New `CompletionDataTest.kt` |
| Persistence | `UiMessage` with `completionData` writes/reads via `MessageStateHandler` | New integration test that writes then re-reads `ui_messages.json` |
| Resume path | Old `text`-only COMPLETION_RESULT and new `completionData`-bearing messages both render | React Testing Library on `ChatView` with mock messages |
| UI variants | Each kind's card renders with expected accent / label / body layout | `CompletionCard.test.tsx` — 3 tests minimum |
| System prompt | Wording changes reflected in 7 snapshots | Snapshot regeneration + diff review |
| End-to-end | Live `runIde` smoke: trigger each kind from tool-testing panel, eyeball card | Manual |

**Out of scope for testing:**
- LLM classification accuracy (measured post-ship via telemetry, not at build time).
- Cross-browser JCEF rendering edge cases.

---

## 8. Migration & Backward Compatibility

Old `ui_messages.json` entries contain `text: "<result string>"` with no `completionData`. Handled without any migration script:

1. **Kotlin read path.** `UiMessage.completionData` is nullable with default `null`. Old JSON files deserialise cleanly — `kotlinx-serialization` ignores missing fields when a default exists.
2. **React render path.** `ChatView.tsx` checks `msg.completionData`:
   - Present → `<CompletionCard data={msg.completionData} />`
   - Absent but `msg.ask === 'COMPLETION_RESULT'` → `<CompletionCard data={{kind: 'done', result: msg.text ?? ''}} />` (synthesised `done` kind)
3. **Legacy TOOL-message upgrade path** (`chatStore.ts:1520+`): old `attempt_completion` stored as generic TOOL messages (from the very first versions) already synthesise a `result` string; extend the existing upgrade to also set `completionData: {kind: 'done', result}`.

Net effect: every previously-completed session reopens and renders exactly as it did before — as a green `done` card. No lossy migration, no user-visible regression, no script to write or run.

**Wire format stability.** `ui_messages.json` gains an optional nested `completionData` object on `COMPLETION_RESULT` messages. Downstream tooling that reads these files (if any) sees an additive change.

---

## 9. Observability & Rollout

### Pre-ship

Phases 1–4 execute in order on the **existing `feature/tooling-architecture-enhancements` branch / worktree**. No new branch. No worktree switch. Each phase gets its own commit on this branch with passing tests for that phase.

### Ship criteria

- All tests green on the branch (including regenerated snapshots).
- Manual QA in `runIde`: one task per kind, each card renders correctly; one old session opens cleanly in fallback mode.
- Showcase page shows all three variants.

### Post-ship telemetry

Watch `SessionMetrics.completionKindCounts` distribution over the first week:
- If ≥95% `done` → classification is degenerate; tighten description.
- If >30% `review` or `heads_up` → LLM is over-flagging; tighten criteria with stricter examples.
- If ≥20% `heads_up` without a corresponding `discovery` of real value → the kind is being misused as a catch-all; consider retiring or reframing.

No automated alerts; manual review after 7 days and again at 30 days.

---

## 10. Open Questions

None that block execution. Items flagged for post-v1 reconsideration, once telemetry is in:

- **Do we want a "Continue this task" affordance on any kind?** Deferred until we have data on how often users type a continuation message after a `review` or `heads_up` card.
- **Should `heads_up` discoveries auto-write to archival memory?** Currently requires LLM to separately call a memory tool. Could be automated but has "surprise writes" risk.
- **Is `review` pulling its weight?** If telemetry shows `review` is rarely picked, we may collapse to two kinds (`done` / `heads_up`). Don't preempt.
- **Cross-IDE verify_how semantics.** `./gradlew test` makes sense on macOS/Linux but not necessarily in a bare PyCharm Windows session. Worth watching if users report the verify pill containing shell-specific commands in the wrong IDE.

---

## Appendix A: The `verify_how` / `command` bug resolution

This redesign fixes the original bug by construction, not by patch:

1. **Field lives on a typed side-car.** `CompletionData.verifyHow: String?` is a first-class field on the persisted message — never stuffed into `text` as a JSON-encoded blob. The `chatStore.ts:617-628` JSON hack is **deleted**, not fixed.
2. **End-to-end type safety.** From `AttemptCompletionTool.execute()` → `ToolResult.completionData` → `LoopResult.Completed` → Kotlin bridge → `window._appendCompletionCard({...})` → `UiMessage.completionData` → `<CompletionCard data={...} />`, every hop is typed as `CompletionData`. The TS type, the Kotlin data class, and the JSON wire format all share a single definition.
3. **Persistence carries it.** `UiMessage.completionData` writes into `ui_messages.json` on every save. Resume reads it back. The previous drop at `AgentLoop.kt:1411-1416` can't happen because the persistence path no longer has a choice — there's no `text`-field corner to stuff data into or drop data from.
4. **UI prop signature prevents the same class of bug.** `CompletionCard` now takes `data: CompletionData`, not `result: string` + `verifyCommand?: string`. A caller cannot accidentally pass one without the other — they either pass the whole structured object or TypeScript errors.

The redesign is therefore the fix, plus the kind enum, plus the per-kind UX.

---

## Appendix B: Summary of discussion decisions that were rejected

For context when reading the plan — each rejected option and why:

- **5 kinds including `blocked`.** Rejected: overlapped with `ask_followup_question`; would have forced new suspend-vs-exit loop semantics.
- **4 kinds including `partial`.** Rejected: created a slack-off escape hatch for the LLM. Honest partial progress is now communicated via the `result` narrative inside `done`.
- **Per-kind separate tools (`complete_done`, `complete_review`, etc.).** Rejected: fragmented the exit signal, violated invariant A ("only one way to stop iteration").
- **`<thinking>` self-classification layer.** Rejected: Sourcegraph Cody hides thinking output; unobservable layer is worthless.
- **`kind_reason: string?` justification field.** Rejected: gameable, bloats schema, the enum itself is sufficient forced classification.
- **Multi-label kind (one primary + modifiers).** Rejected: doubles card-state complexity, makes UI rendering conditional on combinations.
- **`verify_how` restricted to `review` only.** Rejected: pressured LLM to misclassify `done` tasks as `review` just to attach a verify command. Orthogonal is cleaner.
- **Auto-resume for `partial` on session reopen.** Moot (partial removed), but also rejected as surprising UX.
- **"Continue this task" button on `partial`.** Moot (partial removed). If resurfaced for other kinds, treated as v2 UX work.

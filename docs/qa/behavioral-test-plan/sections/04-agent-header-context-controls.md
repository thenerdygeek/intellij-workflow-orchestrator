# Agent Chat — Header, Context Meter & Controls

This section covers the Agent chat **top bar** and the in-flow **controls** that sit
around the conversation: the context/token budget meter, the session stats chips
(tokens + cost), the session title, the model selector, plan-mode toggle and plan
cards, the approval gate, the question wizard, process-stdin input, the compaction
overlay/marker, and the background/monitor/skill indicators plus the whole-task vs
per-tool Stop controls.

It is written for a tester driving `./gradlew runIde` on licensed Windows IntelliJ
IDEA Ultimate. Sourcegraph tokens are configured. Every scenario is traced to source.

> **USER PRIORITY — verify the numbers, not just movement.** The single most
> important thing to confirm in this section is that the **token count / context %
> / cost / iteration counters update with the *correct* value after each iteration
> and RESET correctly after compaction.** Wherever a scenario says "verify the
> number," cross-check the on-screen value against the agent's session JSONL files
> on disk (see the calibration note below). A meter that merely *moves* is not a
> pass; it must move to the *right* value.

### Reading the ground-truth numbers (calibration)

The agent persists every LLM exchange under
`~/.workflow-orchestrator/{project-slug}-{sha6}/agent/sessions/{sessionId}/api_conversation_history.json`
and the UI mirror in `ui_messages.json`. Token usage comes from the LLM response
and is surfaced two ways:

- **Top-bar Context meter** shows the *current context window fill* — i.e. the most
  recent prompt-token count, **not** a running total. Source path:
  `AgentController.onTokenUpdate(promptTokens, completionTokens)` →
  `dashboard.updateProgress("", promptTokens, displayMaxInputTokens())`
  (`AgentController.kt:3060-3069`) → `AgentCefPanel.updateTokenBudget`
  (`AgentCefPanel.kt:1367-1368`) → `chatStore.tokenBudget = {used, max}` →
  `TopBar.tsx:32-34`. So `used` = last prompt tokens, `max` = the per-model window
  for the **selected** model (`displayMaxInputTokens()` → `AgentController.kt:5056-5057`).
- **Below-input UsageIndicator** reads live from the `ContextManager` every second
  via `window.workflowAgent.getContextUsage()` → `_getContextUsage`
  (`AgentCefPanel.kt:933-936`); `used = currentInputTokens()`, `max = maxInputTokensFor(model)`
  (`UsageIndicator.tsx:29-70`).
- **Right-side SessionStatsChips** show **cumulative** `tokensIn/tokensOut` and est.
  cost for the whole session (plus sub-agent accumulation):
  `AgentController.onSessionStats` (`AgentController.kt:3071-3076`) →
  `updateSessionStats` (`AgentCefPanel.kt:1816-1824`) → `SessionStatsChips.tsx:19-38`.

Keep a file watcher / second editor on `api_conversation_history.json` open while
testing so you can compare.

---

## A. Context / Token Meter (USER PRIORITY)

### [HDR-1] Context meter appears and formats correctly once token data exists
- **Component(s):** TopBar.tsx (meter block 78-133), chatStore `tokenBudget`
- **Preconditions:** Fresh session, no messages yet.
- **Steps:**
  1. Open the Workflow tool window → Agent tab. Observe the top-left of the header before sending anything.
  2. Send a simple prompt (e.g. "List the files in the project root").
  3. After the first LLM response lands, read the meter: the "CONTEXT" label, the 48px bar, and the `NN% (NNK)` text.
- **Expected — visual:** Before any data the left shows only the muted text `Agent` (TopBar.tsx:134-138). After the first response, "CONTEXT" (uppercase, muted) + a rounded mini-bar (48px wide) + `NN% (NNK)` in tabular-nums. The percent text color matches the bar fill color.
- **Expected — behavioral:** `tokenLabel` is `${fillPercent.toFixed(0)}% (${formatK(used)})` (TopBar.tsx:45-53). `formatK`: ≥1M → `1.2M`, ≥1000 → `60K` (no decimal), else raw integer. Hovering the meter shows tooltip `Context: {used} / {max} tokens ({pct.toFixed(1)}%)` with thousands separators (TopBar.tsx:79).
- **✅ Checks (tick each):**
  - [ ] Pre-data state shows `Agent`, no bar.
  - [ ] Post-response shows CONTEXT label + bar + `NN% (NNK)`.
  - [ ] The `(NNK)` value, multiplied out, equals the meter tooltip's `used` figure (e.g. `60K` ↔ `Context: 60,123 / …`).
  - [ ] Tooltip `used / max` and `pct.toFixed(1)` match the bar.
- **🐞 Bug signals:** `(NaNK)` or `Infinity%`; `(NNK)` and tooltip disagree; bar visible but `max` is 0; decimals on the K (should be `60K`, not `60.1K`).
- **Theme/size matrix:** light + dark (+ narrow width)
- **⛔ Write note:** read-only prompt; no write tools involved.

### [HDR-2] Token count updates after EACH iteration and matches the JSONL prompt tokens
- **Component(s):** TopBar meter; `AgentController.onTokenUpdate` (AgentController.kt:3060-3069)
- **Preconditions:** A task that will take several tool-call iterations (e.g. "Read three different source files and summarize each").
- **Steps:**
  1. Open `api_conversation_history.json` for the session in a second editor (or `tail` it).
  2. Send the multi-step prompt and watch the meter through each iteration.
  3. After the run settles, compare the meter's final `used` against the last prompt-token count the agent logged.
- **Expected — visual:** The `NN% (NNK)` value changes after each model round-trip (each new tool result grows the context). The bar width grows in step.
- **Expected — behavioral:** `used` is the **latest** prompt-token count, not a sum. It should rise as the conversation grows, and it is keyed to the selected model's `max` (`displayMaxInputTokens()`).
- **✅ Checks (tick each):**
  - [ ] Meter `used` increments at iteration boundaries (not per streamed token — it updates once per API response).
  - [ ] The final on-screen `used` equals (within rounding) the prompt/input token count of the last exchange in the JSONL.
  - [ ] `fillPercent` ≈ `used / max * 100` for the selected model's `max`.
  - [ ] It does **not** keep climbing while only streaming text within one response.
- **🐞 Bug signals:** Meter shows a cumulative total (keeps only ever growing far past the model window); meter frozen at the first value; `used` far larger than any single prompt in the JSONL (indicates summing).
- **Theme/size matrix:** light + dark (+ narrow width)
- **⛔ Write note:** read-only task; keep it to reads/searches.

### [HDR-3] Bar + text color thresholds (80% / 88% / 97%)
- **Component(s):** TopBar `tokenColor` (TopBar.tsx:37-43), bar pulse (TopBar.tsx:96)
- **Preconditions:** Ability to push the context high. Easiest: set a **small context-window override** (e.g. 8K) so normal use crosses the thresholds quickly; or run a long read-heavy task. **Full navigation path:** Settings (`Ctrl/Cmd+Alt+S`) → **Tools → Workflow Orchestrator → AI Agent → Advanced → "Context Window" group → "Max input tokens for all models (0 = model default)"** (global override `maxTokenGlobalOverride`), or use the per-model override table just below it (`maxTokenPerModelOverrideJson`) to cap only the active model.
- **Steps:**
  1. Drive the context up gradually (repeated reads of large files) and watch the bar + percent text color flip at the boundaries.
- **Expected — visual:** `< 80%` → muted/secondary gray (`--fg-secondary`); `80–88%` → amber (`--accent-edit`); `≥ 88%` → red (`--error`); `≥ 97%` → red **and the bar fill pulses** (`animate-pulse`, TopBar.tsx:96). Both the bar fill and the percent text share the color.
- **Expected — behavioral:** Thresholds are evaluated on `fillPercent` (capped at 100, TopBar.tsx:34).
- **✅ Checks (tick each):**
  - [ ] Gray below 80%.
  - [ ] Amber between 80% and 88%.
  - [ ] Red at/after 88%.
  - [ ] Bar fill visibly pulses only at/after 97%.
  - [ ] Percent text and bar fill are always the same color.
- **🐞 Bug signals:** Amber persists past 88%; no pulse at 97%; bar and text colors diverge; color stuck on the first bucket as percent climbs.
- **Theme/size matrix:** light + dark (+ narrow width) — confirm amber/red are distinguishable in both themes.
- **⛔ Write note:** read-only; revert the context-window override afterward.

### [HDR-4] Status label buckets (Near limit / High / Critical / Compacting…)
- **Component(s):** TopBar `statusLabel` (TopBar.tsx:55-66)
- **Preconditions:** Same high-context setup as HDR-3.
- **Steps:**
  1. Cross 80%, 88%, 97% and read the small uppercase status word right of the percent.
  2. Trigger the Compact button (HDR-9) and watch the label while compaction runs.
- **Expected — visual:** Empty below 80%; `Near limit` at 80–88%; `High` at 88–97%; `Critical` at ≥97%. While an actual compaction is in progress the label is `Compacting…` and it **overrides** all threshold words (TopBar.tsx:60).
- **Expected — behavioral:** `compacting` is driven by `compactionState.active`; it trumps the threshold buckets so the badge reflects a real in-progress op, not just the zone.
- **✅ Checks (tick each):**
  - [ ] No status word under 80%.
  - [ ] `Near limit` / `High` / `Critical` appear at the right zones.
  - [ ] `Compacting…` shows during compaction and replaces any threshold word.
  - [ ] Status word color tracks `tokenColor` (slightly faded).
- **🐞 Bug signals:** `Compressing` (old wording) instead of the new buckets; threshold word shown while compaction is active; label never clears below 80%.
- **Theme/size matrix:** light + dark (+ narrow width)
- **⛔ Write note:** read-only.

### [HDR-5] Meter `used` is context-FILL, not a cumulative total
- **Component(s):** TopBar meter vs SessionStatsChips; onTokenUpdate vs onSessionStats
- **Preconditions:** A run long enough that an in-loop auto-compaction or a model-driven summary fires (or use HDR-9 manual compaction).
- **Steps:**
  1. Note the meter `used` and the right-side `↕ in/out` chip values mid-run.
  2. Continue until the context is reshaped (compaction) — see HDR-9/HDR-10.
  3. Compare how the two move.
- **Expected — visual:** The meter `used` can **drop** when context is compacted/replaced; the `↕ in/out` chip only ever grows.
- **Expected — behavioral:** Meter = latest prompt tokens (resettable); chips = cumulative session totals (monotonic).
- **✅ Checks (tick each):**
  - [ ] Meter `used` and chip `in` are different numbers (meter is one prompt; chip is the session sum).
  - [ ] After compaction the meter `used` decreases but the `↕ in/out` chip does **not**.
- **🐞 Bug signals:** Meter behaves like a cumulative sum; chip drops after compaction (it must not).
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HDR-6] Tooltip exactness on the meter
- **Component(s):** TopBar.tsx:79
- **Preconditions:** Any session with token data.
- **Steps:**
  1. Hover the CONTEXT meter and read the title attribute.
- **Expected — visual:** `Context: {used} / {max} tokens ({pct}%)` with thousands separators (`toLocaleString`) and one decimal on the percent.
- **Expected — behavioral:** `used`/`max` come straight from `tokenBudget`; `pct` = `fillPercent.toFixed(1)`.
- **✅ Checks (tick each):**
  - [ ] `used` has thousands separators.
  - [ ] `max` equals the selected model's window (cross-check HDR-15).
  - [ ] `pct` to one decimal matches the rounded percent in the visible label.
- **🐞 Bug signals:** Missing separators; `max` = 0; percent mismatch between tooltip and label.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HDR-7] Below-input UsageIndicator: format, thresholds, and agreement with the meter
- **Component(s):** UsageIndicator.tsx (full)
- **🔗 Cross-ref:** Pairs with **IN-29** (sections/01 — "Context usage indicator (numeric / percent / color)"). **Per README §4 S3, `UsageIndicator` appears NOT mounted in the live app** (referenced only by `HarnessApp.tsx`/showcase; `App.tsx`/`InputBar.tsx` never render it) — so **first confirm whether the below-input line renders at all**. If it is absent, the meter-vs-indicator agreement check cannot be exercised and that is the known S3 gap (see also S12, which folds into S3), not a fresh regression.
- **Preconditions:** Any active session.
- **Steps:**
  1. Read the small line directly under the chat input: `context: NNK / NNK used (NN%)`.
  2. Compare its `used`/`max`/`%` against the top-bar meter at the same instant.
- **Expected — visual:** Text `context: {usedK}K / {maxK}K used ({pct}%)` where `usedK = round(used/1000)`, `maxK = round(max/1000)` (UsageIndicator.tsx:69-79). Color: `< 50%` gray `#888`, `50–80%` amber `#d97706`, `> 80%` red `#dc2626` (UsageIndicator.tsx:68). Tooltip shows full `used / max` with separators.
- **Expected — behavioral:** It reads from the **same** ContextManager fill as the meter, so the rounded numbers should agree. **Its color thresholds intentionally differ from the meter's** (50/80 here vs 80/88/97 in the meter), so at e.g. 60% the bottom line is amber while the top meter is still gray — that divergence is by design; the *numbers* must still match.
- **✅ Checks (tick each):**
  - [ ] `usedK`/`maxK`/`%` round-match the top-bar meter's `used`/`max`/`%`.
  - [ ] Color flips at 50% and 80% (independent of the meter's flips).
  - [ ] Tooltip shows the precise `used / max`.
  - [ ] Before the bridge is ready it shows `0K / 132K used (0%)` rather than crashing.
- **🐞 Bug signals:** The two indicators show **different** `used`/`max` numbers (a real bug — same source); `Infinity%` when max is 0; stuck at `0/132K` after data exists.
- **Theme/size matrix:** light + dark (+ narrow width — the line should not wrap/clip)
- **⛔ Write note:** read-only.

### [HDR-8] UsageIndicator polling, visibility pause, and event refresh
- **Component(s):** UsageIndicator.tsx:26-65; `refreshContextUsage` (AgentCefPanel.kt:1419-1420)
- **Preconditions:** Active session.
- **Steps:**
  1. While the agent is working, confirm the bottom line updates roughly every second.
  2. Switch the IDE to another tool window / minimize so the page is hidden, then return.
  3. Trigger a compaction or a `new_task`/handoff and watch the bottom line update **immediately** (not waiting a full second).
- **Expected — visual:** ~1s cadence updates; refreshes at once on compaction/handoff.
- **Expected — behavioral:** Polling pauses while `document.hidden`; the `wf-context-usage-refresh` event forces an immediate tick even while paused (UsageIndicator.tsx:51-59).
- **✅ Checks (tick each):**
  - [ ] Updates ~1×/sec during work.
  - [ ] After compaction the bottom line drops without a perceptible 1s lag.
  - [ ] No console errors when the page was hidden and re-shown.
- **🐞 Bug signals:** Indicator frozen after returning from hidden; no immediate refresh post-compaction; runaway CPU while hidden.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HDR-9] Manual Compact button → overlay, marker, and meter RESET (verify the drop value)
- **Component(s):** TopBar compact button (TopBar.tsx:118-132); CompactionOverlay.tsx; CompactionMarker.tsx; `compactContext` (AgentController.kt:1709-1784)
- **Preconditions:** A session with several exchanges so there is something to compact. Agent **idle** (compact is blocked while the loop runs).
- **Steps:**
  1. Note the meter `used` / percent and the `↕ in/out` chip.
  2. Click the chevrons "Compact context" icon in the meter group.
  3. Watch the sticky overlay, then the divider marker inserted into the scrollback, then the meter.
- **Expected — visual:** A sticky banner appears at the top of the chat: spinner + phase text + "Input disabled until done" (CompactionOverlay.tsx). When done, a centered pill divider appears in the transcript: ✨ "Compacted with LLM summary" (or ✂ "Compacted"), `−NNK tokens`, `−N msgs` (CompactionMarker.tsx:25-63). The meter percent/`used` drops; status label reads `Compacting…` during the op.
- **Expected — behavioral:** Button is disabled while `busy || compacting` (TopBar.tsx:120). `force=true` runs the full pipeline. The marker tooltip shows `messagesBefore → messagesAfter` and `tokensBefore → tokensAfter` and `saved` (CompactionMarker.tsx:48-55).
- **✅ Checks (tick each):**
  - [ ] Overlay shows with spinner and the input is disabled during the op.
  - [ ] Marker's `−NNK tokens` equals `tokensBefore − tokensAfter` from its tooltip.
  - [ ] Meter `used` after compaction ≈ the marker's `tokensAfter` (and the bottom UsageIndicator drops to the same).
  - [ ] `↕ in/out` chip is **unchanged** by compaction.
  - [ ] If clicked while the agent is running, a warning status appears and nothing compacts.
- **🐞 Bug signals:** Meter `used` does **not** drop after the marker shows `tokensAfter` lower (the top meter can lag because it only refreshes on the next API call — flag if it never catches up even after the next message); marker tokens math inconsistent; chip wrongly drops; overlay never clears (input stays disabled).
- **Theme/size matrix:** light + dark (+ narrow width — pill divider must wrap gracefully)
- **⛔ Write note:** no write tools; compaction is internal.

### [HDR-10] Auto-compaction during a long run (88% gate) → marker + meter drop
- **Component(s):** ContextManager 88% gate; CompactionMarker; meter
- **Preconditions:** Drive context to ≥88% (small per-model window override from HDR-3 makes this fast) during an active task.
- **Steps:**
  1. Run a read-heavy task until the meter crosses ~88% and the loop auto-compacts mid-run.
- **Expected — visual:** A compaction marker appears in-flow without you clicking Compact; the meter drops on the subsequent iteration; the `↕ in/out` chip keeps climbing.
- **Expected — behavioral:** Auto-compaction is the single 88% utilization gate; the meter's next `used` reflects the summarized history.
- **✅ Checks (tick each):**
  - [ ] Marker appears automatically near 88%.
  - [ ] Meter `used` decreases on the next API response after the marker.
  - [ ] `↕ in/out` chip continues to rise (cumulative).
  - [ ] Bottom UsageIndicator also drops.
- **🐞 Bug signals:** Loop wedges at ~88% with no compaction; meter never recovers; double markers for one compaction.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only task; revert the window override after.

### [HDR-11] Meter MAX re-keys to the selected model's window
- **Component(s):** `displayMaxInputTokens()` (AgentController.kt:5056-5057); `notifyContextWindowOverridesChanged` (AgentController.kt:5066-5069)
- **Preconditions:** Two models with different windows available in the picker (e.g. a 132K vs a ~93K thinking variant).
- **Steps:**
  1. Note the meter tooltip `max` and the bottom UsageIndicator `maxK`.
  2. Switch the model via the picker (HDR-16).
  3. Re-read both `max` values.
- **Expected — visual:** `max` (and therefore the percent, since `used` is unchanged momentarily) changes to the newly selected model's window. The percent recomputes against the new `max`.
- **Expected — behavioral:** The display `max` is keyed on the **selected** model, not the running brain, so it matches the picker row's capacity strip immediately.
- **✅ Checks (tick each):**
  - [ ] Meter tooltip `max` matches the selected model's window.
  - [ ] Bottom UsageIndicator `maxK` matches.
  - [ ] Percent recomputes (a smaller window → higher percent for the same `used`).
- **🐞 Bug signals:** `max` stuck on the prior model; meter and picker row disagree on the window.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HDR-12] No-token-data / fresh-session state
- **Component(s):** TopBar.tsx:33, 134-138
- **Preconditions:** Brand-new chat (New button), nothing sent.
- **Steps:**
  1. Click "New" and look at the header before sending.
- **Expected — visual:** Left shows the muted `Agent` text, no bar, no percent. The bottom UsageIndicator shows `0K / 132K used (0%)` (default) until the bridge supplies real data.
- **Expected — behavioral:** `hasTokenData = max > 0`; with no data the meter block is suppressed.
- **✅ Checks (tick each):**
  - [ ] `Agent` text shown, no bar.
  - [ ] No `NaN`/`Infinity` anywhere.
  - [ ] Bottom indicator shows the 0/132K default, not a crash.
- **🐞 Bug signals:** Phantom bar at 0%; `Infinity%`; leftover meter from the previous session after New.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

---

## B. Session Stats Chips (cumulative tokens + cost)

### [HDR-13] Tokens chip: cumulative in/out, format, tooltip
- **Component(s):** SessionStatsChips.tsx:19-27
- **Preconditions:** A session with at least one response.
- **Steps:**
  1. Read the `↕ NN/NN` chip on the header's right side; hover for the tooltip.
  2. Send another message and watch the values grow.
- **Expected — visual:** `↕ {in}/{out}` in tabular-nums, muted color. `formatTokens`: ≥1M → `1.2M`, ≥1000 → `1.5K` (one decimal), else raw. Tooltip: `Tokens: {in} in / {out} out (cumulative this session)` with separators.
- **Expected — behavioral:** Only rendered when `tokensIn>0 || tokensOut>0` (SessionStatsChips.tsx:19). Values include sub-agent accumulation (added in `onSessionStats`).
- **✅ Checks (tick each):**
  - [ ] `↕ in/out` monotonically increases across messages.
  - [ ] One-decimal K formatting (`1.5K`), distinct from the meter's no-decimal K.
  - [ ] Tooltip values match the chip (de-abbreviated).
  - [ ] Cross-check against summed input/output tokens across exchanges in the JSONL — they should agree.
- **🐞 Bug signals:** Chip resets to 0 mid-session; numbers disagree with the JSONL sum; chip shown when both are 0.
- **Theme/size matrix:** light + dark (+ narrow width — chips must not push the New button off-screen)
- **⛔ Write note:** read-only.

### [HDR-14] Cost chip: threshold, formatting, click-through to Insights
- **Component(s):** SessionStatsChips.tsx:9-10, 29-38
- **Preconditions:** Run enough to accrue est. cost ≥ $0.005.
- **Steps:**
  1. Watch for the `≈ $X.XX` chip to appear; hover for the tooltip; click it.
- **Expected — visual:** Cost only shows once `estimatedCostUsd >= 0.005`. Format: `≈ $0.02` (2 decimals at ≥$0.01) or `≈ $0.007` (3 decimals below $0.01). Tooltip: "Est. cost at public list pricing. Click to open Insights." Clicking opens the Insights tab (`openInsightsTab`).
- **Expected — behavioral:** Cost is cumulative this session; null/`<0.005` → chip hidden.
- **✅ Checks (tick each):**
  - [ ] No cost chip below $0.005.
  - [ ] 3-decimal form under $0.01, 2-decimal at/above.
  - [ ] Click opens Insights.
  - [ ] Cost grows monotonically alongside tokens.
- **🐞 Bug signals:** Cost shown at $0.000; jumps backward mid-session; click does nothing.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HDR-15] Chips are cumulative — they must NOT reset on compaction; duration/iteration are NOT in the chips
- **Component(s):** SessionStatsChips.tsx (full); compare meter
- **Preconditions:** A session you can compact (HDR-9).
- **Steps:**
  1. Record `↕ in/out` and cost.
  2. Compact the context.
  3. Re-read the chips.
- **Expected — visual:** Chips unchanged by compaction (only the meter dropped). The header has **no separate duration or iteration-count chip** — `SessionStatsChips` renders only tokens + cost (confirmed in source).
- **Expected — behavioral:** Cumulative totals survive compaction; per-session duration/iterations are persisted in `SessionInfo` (`completeSession`) but are not surfaced as live header chips.
- **✅ Checks (tick each):**
  - [ ] `↕ in/out` and cost identical before/after compaction.
  - [ ] No duration ("Nm Ns") or "N iterations" text in the header chip group.
- **🐞 Bug signals:** Chips drop after compaction; an iteration/duration chip appears that the spec doesn't define (note it as an unexpected addition).
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

---

## C. Model Selector

### [HDR-16] Open the model dropdown; list models; capacity + capability strips
- **Component(s):** InputBar.tsx ModelChip (146-260), ModelPickerRow (88-142)
- **Preconditions:** Models loaded (catalog fetched).
- **Steps:**
  1. Click the "Model" chip on the input bar's left controls.
  2. Read each row: model name, capacity strip, capability badges.
- **Expected — visual:** Dropdown of models; each row shows `132K context · 18K per-message` style capacity and capability badges (👁 vision · 🔧 tools · 🧠 reasoning · ⚠ deprecated). The currently active model row is highlighted. Empty catalog → "No models available" (InputBar.tsx:225).
- **Expected — behavioral:** First open requests the list (`_requestModelList`); legacy payloads without `contextWindow`/`capabilities` render gracefully (badge strip omitted).
- **✅ Checks (tick each):**
  - [ ] Dropdown lists ≥1 model; active row highlighted.
  - [ ] Capacity numbers per row look sane (match the meter `max` when that model is selected).
  - [ ] Badges render (vision/tools/reasoning where applicable).
  - [ ] Deprecated models show the ⚠ badge.
- **🐞 Bug signals:** Empty dropdown after catalog load; garbled badges; capacity strip absent for all rows even though catalog loaded.
- **Theme/size matrix:** light + dark (+ narrow width)
- **⛔ Write note:** read-only.

### [HDR-17] Switch model → label updates, applies next iteration, fallback badge clears
- **Component(s):** `_changeModel` → `changeModel` (AgentController.kt:5071-5087)
- **Preconditions:** ≥2 models available.
- **Steps:**
  1. With the agent idle, pick a different model from the dropdown.
  2. Confirm the chip label changes immediately.
  3. Send a new message and confirm the new model is used from the next iteration.
- **Expected — visual:** Chip label updates to the new model's display name at once. The meter `max` re-keys (HDR-11).
- **Expected — behavioral:** `changeModel` writes `sourcegraphChatModel`, calls `setModelName`, and `requestModelChange` so the brain swaps at the **next iteration boundary** (not mid-response). Any "fallback active" badge is cleared (`setModelFallbackState(false, null)`).
- **✅ Checks (tick each):**
  - [ ] Chip label reflects the chosen model immediately.
  - [ ] The next response uses the new model (cross-check the model id in the JSONL / Insights).
  - [ ] Meter `max` updates to the new model's window.
  - [ ] A previously shown fallback badge disappears after a manual switch.
- **🐞 Bug signals:** Label updates but follow-up messages keep the old model (the regression `changeModel` exists to fix); model swaps mid-stream and corrupts the turn.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [HDR-18] Automatic-fallback model badge
- **Component(s):** `setModelFallbackState` (AgentCefPanel.kt:1385-1387); ModelChip fallback indicator (InputBar.tsx:196-225)
- **Preconditions:** Hard to force deterministically — a NETWORK_ERROR/timeout that exhausts recycles and escalates one tier down the chain (requires `networkErrorStrategy` = `model_fallback`/`context_compaction`). Best-effort: briefly drop the VPN/tunnel mid-run if your environment allows, then restore.
- **Skip condition:** **Skip this scenario if you cannot safely drop the network on the test box** (e.g. shared/remote box where killing connectivity is disruptive). If skipped or not reproducible, record it under Open questions rather than marking a failure.
- **Steps:**
  1. Induce a tier escalation (or simulate by toggling network) and watch the model chip.
- **Expected — visual:** The chip shows a fallback indicator with a tooltip giving the human-readable reason ("Automatic fallback model active" / specific reason).
- **Expected — behavioral:** The badge persists until a manual model change clears it (HDR-17).
- **✅ Checks (tick each):**
  - [ ] Fallback badge + tooltip appears on auto-escalation.
  - [ ] Manual model pick clears it.
- **🐞 Bug signals:** No badge after a known fallback; badge sticks after a manual change.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only. *(See the **Skip condition** above if you cannot reliably trigger this.)*

---

## D. Plan Mode & Plan Cards

### [HDR-19] Plan-mode toggle on/off; write tools refused while ON
- **Component(s):** InputBar PlanChip (263-290), `_togglePlanMode`; plan-mode execution guard
- **Preconditions:** Idle session, a throwaway scratch file in the project (e.g. `scratch-qa.txt`).
- **Steps:**
  1. Click the "Plan" chip — it should highlight (active).
  2. Ask the agent to *write/modify* the scratch file (e.g. "Add a line to scratch-qa.txt").
  3. Toggle Plan off and confirm the chip de-highlights.
- **Expected — visual:** Active Plan chip is accent-colored/filled (InputBar.tsx:280-281); tooltip toggles "Enable/Disable plan mode".
- **Expected — behavioral:** In plan mode the LLM is not offered write tools (schema-filtered) and the loop guard blocks mutating tools even if hallucinated; the agent should respond with a **plan**, not an edit. No file is written.
- **✅ Checks (tick each):**
  - [ ] Plan chip highlights when ON.
  - [ ] A write request produces a plan/refusal, NOT an edit (scratch file unchanged on disk).
  - [ ] Toggling off restores normal (Act) behavior.
- **🐞 Bug signals:** A write tool actually runs while plan mode is ON (data-safety bug); chip state and actual mode disagree.
- **Theme/size matrix:** light + dark (+ narrow width)
- **⛔ Write note:** Plan mode must REFUSE the write; if any approval card appears for a write here, that is a bug — do not approve. Only the scratch file would ever be a legal target.

### [HDR-20] PlanSummaryCard rendering (header, badge, typewriter, buttons)
- **Component(s):** PlanSummaryCard.tsx (full); ChatFooter.tsx:152
- **Preconditions:** Plan mode ON; ask for a multi-step plan ("Plan how you'd add input validation to X").
- **Steps:**
  1. Let the agent present the plan; observe the card.
- **Expected — visual:** Card with FileText icon, plan title (truncated), a badge reading "Awaiting Approval" (or "N comments" if you've added comments), a summary preview that reveals with a one-shot typewriter animation (PlanSummaryCard.tsx:33-106), and a footer: Dismiss / View Implementation Plan / **Approve** (glow button). The typewriter shows a blinking caret while revealing.
- **Expected — behavioral:** The typewriter animates only the first time per plan identity/`revision`; re-render shows full text instantly. `Approve` shows a spinner "Approving…" once clicked.
- **✅ Checks (tick each):**
  - [ ] Title + "Awaiting Approval" badge present.
  - [ ] Summary types out once, then stays.
  - [ ] Dismiss / View / Approve buttons present and styled.
- **🐞 Bug signals:** Typewriter re-runs on every chat append; "rendered fewer hooks" crash; card persists after approval (it should disappear once `plan.approved`).
- **Theme/size matrix:** light + dark (+ narrow width — buttons should wrap/fit)
- **⛔ Write note:** no writes yet — this is the plan presentation.

### [HDR-21] View Implementation Plan → PlanDocumentViewer (line comments)
- **Component(s):** PlanSummaryCard `handleViewPlan` (`_focusPlanEditor`); PlanDocumentViewer.tsx
- **Preconditions:** A plan card is showing.
- **Steps:**
  1. Click "View Implementation Plan" — a full editor tab opens with the plan document.
  2. Hover a block's gutter, click the `+`, type a comment, submit (Cmd/Ctrl+Enter), then remove it (×).
- **Expected — visual:** Line-numbered blocks; `+` comment buttons in the gutter; an inline comment editor with Comment/Cancel; existing comments render as bubbles with an × remove (PlanDocumentViewer.tsx:246-305). Markdown renders admonitions, task checkboxes, code blocks; links open in the IDE.
- **Expected — behavioral:** Submitting a comment bumps the plan's comment count (the summary card badge flips to "N comments" and offers **Revise (N)** instead of Approve). Esc cancels the editor.
- **✅ Checks (tick each):**
  - [ ] Plan opens in an editor tab with line numbers.
  - [ ] Adding a comment shows a bubble + increments the card's comment count.
  - [ ] Removing a comment decrements it.
  - [ ] File links in the plan navigate in the IDE.
- **🐞 Bug signals:** Comment count out of sync between the editor and the summary card; Cmd/Ctrl+Enter doesn't submit; markdown/code blocks mis-render.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** Comments are plan metadata, not file writes — safe.

### [HDR-22] Approve plan → switches to Act, write tools run (scratch file), PlanApprovedBubble
- **Component(s):** PlanSummaryCard `handleApprove` (`_approvePlan`); PlanApprovedBubble.tsx
- **Preconditions:** A plan whose steps target ONLY the scratch file (steer the agent: "Plan a one-line change to scratch-qa.txt"). Plan card showing, no comments.
- **Steps:**
  1. Click **Approve**.
  2. Watch the mode flip to Act and the agent begin executing; approve the write **only** for the scratch file when prompted (HDR-23/HDR-24).
- **Expected — visual:** Approve shows the spinner, then a right-aligned **PlanApprovedBubble** ("Implementation plan approved" + a "View implementation plan" link) appears (PlanApprovedBubble.tsx). The Plan chip leaves plan mode. The summary card disappears (`plan.approved`).
- **Expected — behavioral:** Only the user can switch plan→act (Approve). After approval the loop resumes with write tools available. "View implementation plan" → `_openApprovedPlan`.
- **✅ Checks (tick each):**
  - [ ] PlanApprovedBubble appears, right-aligned.
  - [ ] Mode returns to Act (Plan chip un-highlights).
  - [ ] The agent now performs writes (against the scratch file, gated by approval).
  - [ ] "View implementation plan" link reopens the plan document.
- **🐞 Bug signals:** Writes run before approval; bubble missing; plan card lingers after approval.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** APPROVE WRITES ONLY against the scratch file. If a step targets any real source path, Deny it.

### [HDR-23] Plan revise path (comments present)
- **Component(s):** PlanSummaryCard footer Revise branch (220-237)
- **Preconditions:** A plan with ≥1 comment (from HDR-21).
- **Steps:**
  1. With comments present, the card shows "Revise (N)" instead of Approve. Click it.
- **Expected — visual:** Badge reads "N comments" (warning color); the primary button is **Revise (N)** (warning-colored), which shows a "Revising…" spinner when clicked.
- **Expected — behavioral:** Revise routes the comments back to the agent (`_revisePlanFromEditor`) to produce an updated plan; a new revision resets the card's pending state and re-runs the typewriter for the new content.
- **✅ Checks (tick each):**
  - [ ] With comments, Approve is replaced by Revise (N).
  - [ ] Revise spinner shows; a revised plan card returns.
  - [ ] The revised plan re-animates its summary (new `revision`).
- **🐞 Bug signals:** Approve still shown despite comments; Revise does nothing; revised plan doesn't reset the button.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** revision is plan-only — no writes.

### [HDR-24] PlanProgressWidget (task list) shows and auto-hides
- **Component(s):** PlanProgressWidget.tsx; ChatFooter.tsx:154
- **Preconditions:** A task that makes the agent create typed tasks (multi-step work).
- **Steps:**
  1. During a multi-step Act run, watch for the "Tasks" card.
  2. Let all tasks complete.
- **Expected — visual:** A "Tasks" card lists up to 3 visible todos with status (pending/in_progress/completed); in-progress items show their `activeForm` label. When nothing is pending/in_progress, the widget disappears (PlanProgressWidget.tsx:44).
- **Expected — behavioral:** Hidden when `todos.length === 0` OR no active work.
- **✅ Checks (tick each):**
  - [ ] Tasks card appears while work is pending/in-progress.
  - [ ] In-progress task shows the active-form spinner label.
  - [ ] Card vanishes once all tasks are done.
- **🐞 Bug signals:** Card lingers after completion (visual noise); status icons wrong; never appears for a task that clearly created todos.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-heavy task is fine; if tasks include writes, gate them to the scratch file.

---

## E. Approval Gate

### [HDR-25] edit_file approval card (diff + Allow / Allow-for-session / Deny)
- **Component(s):** ApprovalView.tsx; ApprovalCard.tsx; ChatFooter.tsx:121-138
- **Preconditions:** Act mode; ask the agent to edit the scratch file ("Append a comment line to scratch-qa.txt").
- **Steps:**
  1. When the approval card appears, read the diff and the buttons.
  2. Click **Approve** (scratch file only).
- **Expected — visual:** A unified diff (DiffHtml) above an ApprovalCard showing tool title, optional description, metadata rows, and buttons in order: Deny · Allow for session · Approve (ApprovalCard.tsx:179-184). Shield icon (or shield-alert for HIGH/DESTRUCTIVE). After deciding, the card collapses to a receipt ("Approved"/"Denied").
- **Expected — behavioral:** `edit_file`/`create_file`/`revert_file` are session-approvable, so "Allow for session" is offered (ChatFooter.tsx:133). The decision latches against double-clicks (ApprovalCard.tsx:148). The file changes only after Approve.
- **✅ Checks (tick each):**
  - [ ] Diff shows the proposed change to the scratch file.
  - [ ] Deny / Allow for session / Approve all present.
  - [ ] Approve writes the file; receipt shows "Approved".
  - [ ] Double-clicking Approve does NOT double-fire.
- **🐞 Bug signals:** File written before Approve; missing diff; double-decision; receipt mislabels the choice.
- **Theme/size matrix:** light + dark (+ narrow width — diff + buttons must fit)
- **⛔ Write note:** APPROVE ONLY against the scratch file. Deny anything targeting real source.

### [HDR-26] run_command approval (CommandPreview + per-prefix approve; NO allow-for-session)
- **Component(s):** ApprovalView.tsx:44-53 (CommandPreview branch); CommandPreview.tsx; ApprovalCard actions
- **Preconditions:** Act mode; prompt a harmless command ("Run `git status`").
- **Steps:**
  1. Inspect the command preview and the button set.
  2. Approve the command (read-only command), or click "Approve all \"git status\" this session".
- **Expected — visual:** A CommandPreview card: the formatted command (bash highlight) + chips for shell, cwd, optional `separate-stderr`, and any env vars (CommandPreview.tsx:35-43). Buttons: Deny · (Approve all "{prefix}" this session) · Approve. **No "Allow for session"** generic button (run_command is per-invocation).
- **Expected — behavioral:** `run_command` declares `allowSessionApproval=false`, so only the command-prefix allowlist path is offered (ChatFooter.tsx:135). The prefix button approves that prefix for the rest of the session.
- **✅ Checks (tick each):**
  - [ ] Command + shell/cwd chips render correctly.
  - [ ] Generic "Allow for session" is **absent**.
  - [ ] If a safe prefix is offered, approving it suppresses prompts for that prefix afterward (HDR-27).
  - [ ] Approve runs the command; output appears in the tool card.
- **🐞 Bug signals:** "Allow for session" wrongly shown for run_command; prefix button approves the wrong prefix; chips show stale cwd.
- **Theme/size matrix:** light + dark (+ narrow width)
- **⛔ Write note:** keep commands read-only (`git status`, `ls`). Do not approve a command that writes outside the scratch file.

### [HDR-27] Allow-for-session suppresses subsequent prompts for the same tool
- **Component(s):** SessionApprovalStore (AgentController level); ApprovalCard "Allow for session"
- **Preconditions:** Act mode; two consecutive edits to the scratch file.
- **Steps:**
  1. On the first `edit_file` approval, click **Allow for session**.
  2. Ask for a second edit to the scratch file.
- **Expected — visual:** The first edit applies; the **second** edit applies with NO approval card (or the tool row shows an "auto-approved" badge).
- **Expected — behavioral:** The tool is added to the session approval set, so later invocations of the same tool skip the gate for the session. Memory writes are exempt (HDR-28).
- **✅ Checks (tick each):**
  - [ ] First edit: card shown, "Allow for session" clicked.
  - [ ] Second edit: no card; edit proceeds.
  - [ ] New chat resets this (a fresh session re-prompts).
- **🐞 Bug signals:** Second edit still prompts (allow-for-session ineffective); allow-for-session leaks across "New chat".
- **Theme/size matrix:** light + dark
- **⛔ Write note:** scratch file only for both edits.

### [HDR-28] Memory writes are forced per-invocation (ignore prior session approval)
- **Component(s):** MemoryWriteClassifier gate; ApprovalView memory title
- **Preconditions:** Act mode; you've already clicked "Allow for session" on a normal edit_file (HDR-27). Ask the agent to update its memory ("Save a memory note about this project's build command").
- **Steps:**
  1. Watch for the approval card on the memory write even though edit_file was allowed-for-session.
- **Expected — visual:** A per-invocation approval card titled with the memory verb (e.g. "Updating memory · {topic}"), with **no** "Allow for session" option.
- **Expected — behavioral:** Memory writes ignore any prior session approval and are always per-invocation unless `autoApproveMemoryOperations` is on. Reads are never gated.
- **✅ Checks (tick each):**
  - [ ] Memory write still prompts despite a generic allow-for-session.
  - [ ] Card shows the memory-specific title and no "Allow for session".
- **🐞 Bug signals:** A generic edit_file allow-for-session silences memory writes (a security regression).
- **Theme/size matrix:** light + dark
- **⛔ Write note:** Memory lives under the agent dir (a legal write target); approving it is safe. Still verify it's the memory path, not a source file.

### [HDR-29] TopBar "Waiting for approval" pill + scroll-to-approval; Escape & destructive variant
- **Component(s):** TopBar.tsx:141-164; ApprovalCard Escape (166-174) + destructive variant (41-42)
- **Preconditions:** A pending approval (from HDR-25). Scroll the chat up so the card is off-screen.
- **Steps:**
  1. With an approval pending and scrolled away, observe the header pill; click it.
  2. On a fresh approval card, press **Escape**.
  3. Trigger a HIGH/DESTRUCTIVE-risk approval (e.g. a delete) and note the icon/variant — then **Deny**.
- **Expected — visual:** A breathing amber "Waiting for approval" pill in the header (TopBar.tsx:147-163). Clicking it smooth-scrolls the chat to the approval card (`scroll-to-approval`). Destructive risk → shield-alert icon + destructive button styling.
- **Expected — behavioral:** Pill present only while `pendingApproval` is set. Escape triggers Deny/cancel. Destructive variant is used for `HIGH`/`DESTRUCTIVE` risk.
- **✅ Checks (tick each):**
  - [ ] Pill appears while an approval is pending; breathes.
  - [ ] Clicking the pill scrolls to the card.
  - [ ] Escape denies the pending approval.
  - [ ] Destructive risk shows the alert icon + destructive styling.
- **🐞 Bug signals:** Pill persists after the approval resolves; click doesn't scroll; Escape does nothing; destructive op shown with the benign shield.
- **Theme/size matrix:** light + dark (+ narrow width — pill must not overflow the header)
- **⛔ Write note:** Deny destructive operations — do not approve any delete against real files.

---

## F. Question Wizard & Process Stdin

### [HDR-30] QuestionView — single/multi-select, "Other", text, summary, submit (+ reply image caveat)
- **Component(s):** QuestionView.tsx (full); ChatFooter.tsx:156-159
- **Preconditions:** Prompt the agent to ask you questions ("Ask me 3 questions about how I want this configured, with options").
- **Steps:**
  1. Answer a single-select question; advance.
  2. On a multi-select, check options AND type an "Other" value, submit.
  3. On a text question, type free text and submit (Enter).
  4. Use Skip on one, Back on another; reach the "Review Your Answers" page; Edit one answer; Submit All.
  5. Try "Chat about this" on a question, and within that follow-up attempt to attach an image (via the main chat input).
- **Expected — visual:** Step dots (QuestionView.tsx:526-530); option radios/checkboxes; an "Other" row with an inline text field (446-493); per-question Skip / Chat about this / Cancel (502-512); a summary page "Review Your Answers" with per-answer Edit pencils and Submit All / Cancel (328-388); header chip when present (399-410).
- **Expected — behavioral:** Multi-select **unions** checked options with the "Other" value (not overwrite) (251-272). Forward/Back drives the wizard's `showQuestion` index. Text questions submit on Enter (no Shift). "Chat about this" routes the question + your message to chat (`_chatAboutOption`).
- **✅ Checks (tick each):**
  - [ ] Single-select advances on choice.
  - [ ] Multi-select keeps both checked options and the "Other" value in the answer.
  - [ ] Text question submits on Enter; Shift+Enter newlines.
  - [ ] Skip marks "Skipped"; Back returns to the prior question.
  - [ ] Summary page lists all answers; Edit returns to that question, then back to summary; Submit All sends.
  - [ ] An image attached in a "Chat about this" reply is actually delivered (per the reply-image fix) — note if it is dropped.
- **🐞 Bug signals:** Wizard stuck on Q1 (no advance); multi-select Other overwrites checked options; summary flashes prematurely; Edit doesn't return to summary; reply image silently dropped.
- **Theme/size matrix:** light + dark (+ narrow width — options + Other field must fit)
- **⛔ Write note:** read-only; answering questions writes nothing.

### [HDR-31] ProcessInputView — run_command awaiting stdin
- **Component(s):** ProcessInputView.tsx; ChatFooter.tsx:142-150
- **Preconditions:** Act mode; prompt a command that reads stdin (e.g. a script that prompts for input), or a `read`-style interactive command. Approve the command first.
- **Steps:**
  1. When the process blocks for input, the "Process input requested" card appears.
  2. Type input and press Enter (or click Send).
- **Expected — visual:** Card with a breathing amber dot, "Process input requested", the description, the prompt (as code), a "This input will be sent to: {command}" strip, a text field, and a Send button disabled while empty (ProcessInputView.tsx:27-87).
- **Expected — behavioral:** Submitting appends a newline if absent (ProcessInputView.tsx:15-18); Enter (no Shift) submits; empty input is rejected.
- **✅ Checks (tick each):**
  - [ ] Card shows the prompt + target command.
  - [ ] Send disabled until non-empty.
  - [ ] Enter submits; the process consumes the line and continues.
- **🐞 Bug signals:** Input not delivered (process stays blocked); newline not appended (process waits forever); Send fires on empty input.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** use a benign interactive command that does not write outside the scratch file.

---

## G. Indicators, Title & Stop Controls

### [HDR-32] SkillBanner + TopBar skill pill
- **Component(s):** SkillBanner.tsx; TopBar skill pill (167-180)
- **Preconditions:** Activate a skill (toolbar skills dropdown, `/skill-name`, or let the LLM call `use_skill`).
- **Steps:**
  1. Activate a skill; observe both the header inline pill and the banner below the header.
  2. Click "Deactivate" on the banner.
- **Expected — visual:** A compact accent pill (star icon + skill name) in the header left group AND a full-width SkillBanner below the header: star + name + "active" + a Deactivate button (SkillBanner.tsx:14-67).
- **Expected — behavioral:** Both are driven by `chatStore.skillBanner`. Deactivate calls `deactivateSkill()` and both disappear.
- **✅ Checks (tick each):**
  - [ ] Header pill and banner both show the active skill name.
  - [ ] Deactivate clears both.
  - [ ] `role="status"`/`aria-live` banner announces (accessibility).
- **🐞 Bug signals:** Pill shows but banner doesn't (or vice-versa); Deactivate leaves one of them visible; stale skill after new chat.
- **Theme/size matrix:** light + dark (+ narrow width — name truncates, doesn't overflow)
- **⛔ Write note:** read-only.

### [HDR-33] BackgroundIndicator chip + dropdown
- **Component(s):** BackgroundIndicator.tsx
- **Preconditions:** Start a long-running `background_process` (e.g. "Start a long-running process in the background that prints every second").
- **Steps:**
  1. Observe the "N bg" chip; click it to open the dropdown.
  2. Let one process error/exit non-zero (or time out) and re-check the chip color.
- **Expected — visual:** "N bg" chip with gear icon; a green pulsing dot when any process is RUNNING (BackgroundIndicator.tsx:96-104). On error/timeout/non-zero exit the chip turns amber (29-37, 69-79). Dropdown (480px) lists each process: state dot, bgId, label, "state · runtime", with a live 1s runtime tick while open + running (41-48). Empty list → chip hidden (67).
- **Expected — behavioral:** Populated by `chatStore.backgroundProcesses` (pushed from Kotlin); closes on outside-click/Escape.
- **✅ Checks (tick each):**
  - [ ] Chip shows count + green pulse while running.
  - [ ] Dropdown lists processes with correct state + live runtime.
  - [ ] Chip goes amber on a failed/timed-out process.
  - [ ] Chip disappears when no background processes exist.
- **🐞 Bug signals:** Chip stuck at a count after processes end; runtime not ticking; chip never populates (snapshot coercion bug).
- **Theme/size matrix:** light + dark (+ narrow width — 480px dropdown shouldn't clip off-screen)
- **⛔ Write note:** background command should be benign (e.g. a `ping`/counter), not a writer.

### [HDR-34] MonitorIndicator chip + dropdown
- **Component(s):** MonitorIndicator.tsx
- **Preconditions:** Start a monitor (`monitor` tool, e.g. `source=shell` watching a command, or a Bamboo/PR monitor if configured).
- **Steps:**
  1. Observe the "N monitor(s)" chip; open the dropdown.
- **Expected — visual:** Eye-icon chip "N monitor(s)" with a green pulse while any is RUNNING; dropdown (360px) lists id, label, lowercased state (MonitorIndicator.tsx:48-140). Empty → hidden.
- **Expected — behavioral:** Populated by `chatStore.monitorHandles`; an exited monitor shows EXITED/`exited`.
- **✅ Checks (tick each):**
  - [ ] Chip count + pulse correct while monitoring.
  - [ ] Dropdown lists each monitor with its state.
  - [ ] Chip hides when no monitors.
- **🐞 Bug signals:** Chip never populates; pulse persists after all monitors exit; stale monitors after new chat.
- **Theme/size matrix:** light + dark (+ narrow width)
- **⛔ Write note:** read-only monitor (shell watch of a read command, or PR/Bamboo poll).

### [HDR-35] Running sub-agents pill
- **Component(s):** TopBar runningAgentCount pill (28-30, 183-200)
- **Preconditions:** Trigger a sub-agent fan-out ("Use the explorer agent to investigate X and Y in parallel").
- **Steps:**
  1. While sub-agents run, read the header "N agent(s)" green pulsing pill.
- **Expected — visual:** Green pill "N agents" with a pulsing dot while any message has `subagentData.status === 'RUNNING'`.
- **Expected — behavioral:** Count derived from `messages`; clears when all sub-agents finish/are killed.
- **✅ Checks (tick each):**
  - [ ] Pill shows the correct running count.
  - [ ] Singular "1 agent" vs plural "N agents" wording.
  - [ ] Pill clears when sub-agents complete.
- **🐞 Bug signals:** Count off by one; pill persists after sub-agents finish.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** use read-only explorer sub-agents.

### [HDR-36] SessionTitle (center) — provisional set vs scramble animation
- **Component(s):** SessionTitle.tsx
- **Preconditions:** A task that runs to completion (so Haiku renames the session).
- **Steps:**
  1. Start a task — note an initial/provisional title appears instantly (no animation).
  2. On completion, watch the title play a scramble-to-decrypt animation to the final name.
- **Expected — visual:** Centered, truncated title in muted color; plain updates apply instantly; the animated update settles characters left→right over ~700ms (SessionTitle.tsx:33-76).
- **Expected — behavioral:** Animation only fires when `sessionTitleAnimateKey` changes (animated set); plain `setSessionTitle` is instant; empty title renders nothing.
- **✅ Checks (tick each):**
  - [ ] Provisional title appears instantly without scramble.
  - [ ] Final title plays the scramble once and settles to readable text.
  - [ ] Long titles truncate (hover shows full via `title`).
- **🐞 Bug signals:** Scramble loops/never settles; title flickers on every chat append; animation runs on the initial set.
- **Theme/size matrix:** light + dark (+ narrow width — center title must truncate, not push the chips)
- **⛔ Write note:** read-only task is sufficient.

### [HDR-37] Stop — whole-task vs per-tool, plus header New/View/History/Debug
- **Component(s):** InputBar Stop (`_cancelTask`, 626-635 / 1060); ToolCallChain universal Stop (`killToolCall`, 390-407); TopBar right buttons (224-296)
- **Preconditions:** A long task with at least one long-running, stoppable tool (e.g. a slow search or a long read) — NOT run_command/agent/background_process (those suppress the universal Stop).
- **Steps:**
  1. While the agent is busy, click the **Stop** button in the input bar (replaces Send) — the whole task should abort.
  2. Start again; while a stoppable tool is RUNNING, click the **per-tool Stop** on that tool card — only that tool aborts and the loop continues.
  3. Exercise the header right-side buttons: Debug toggle, View in Editor, History, New.
- **Expected — visual:** Input-bar Stop is shown only while busy. Per-tool Stop appears on RUNNING tools except `run_command`/`background_process`/`ask_user_input`/`agent` (ToolCallChain.tsx:32). The stopped tool feeds "[Stopped by user]" back and the loop keeps going. Header: Debug toggle turns red if there are error log entries; View in Editor opens a mirror tab; History opens the session list; New (disabled while busy) clears the chat.
- **Expected — behavioral:** Whole-task Stop = `_cancelTask` (aborts the loop); per-tool Stop = `killToolCall(id)` → `_killToolCall` (aborts one tool, loop continues). New is disabled while `busy`.
- **✅ Checks (tick each):**
  - [ ] Input-bar Stop aborts the whole task (working indicator/ spinner stops).
  - [ ] Per-tool Stop aborts only that tool; the agent proceeds with the next step.
  - [ ] No per-tool Stop on run_command/background_process/agent cards.
  - [ ] Debug toggle reflects error state (red); View opens a mirror; History opens; New disabled while busy then clears chat.
- **🐞 Bug signals:** Per-tool Stop aborts the whole loop; whole-task Stop leaves a zombie spinner; New enabled mid-run; mirror tab blank.
- **Theme/size matrix:** light + dark (+ narrow width — right button group must stay reachable)
- **⛔ Write note:** keep the stoppable tool read-only; do not stop mid-write of a real file.

---

## Open questions / uncertainties

- **Duration / iteration-count chips:** The brief lists "duration / iteration" under
  SessionStatsChips, but the component (`SessionStatsChips.tsx`) renders **only**
  cumulative tokens + cost. Per-session `iterations`/`durationMs` exist in
  `SessionInfo` via `completeSession` (chatStore) but are **not read by any header
  component** (no `s.session` consumer found, and `CompletionCard` renders only
  `CompletionData`). HDR-15 verifies their absence; if the tester sees live
  duration/iteration counters in the header, that is an undocumented addition worth
  flagging.
- **"Move-to-background" whole-task control:** No whole-task "send to background"
  button exists in the agent header. Backgrounding is a `background_process` *tool*
  concept surfaced by the BackgroundIndicator chip (HDR-33). HDR-37 covers
  whole-task Stop vs per-tool Stop only. If a move-to-background affordance is
  expected, it is not implemented in this surface.
- **Top-meter lag after manual compaction (HDR-9):** The top-bar meter `used` is
  only refreshed on the next `onTokenUpdate` (next API call), whereas the bottom
  UsageIndicator polls the ContextManager directly. After an **idle** manual
  compaction the two may briefly disagree (bottom drops immediately; top updates on
  the next message). Confirm whether this is acceptable or a defect for your build.
- **Reply-image attachment (HDR-30):** Image attachment happens via the main chat
  input on a "Chat about this" follow-up, not inside the QuestionView card itself.
  A prior fix addressed a dropped question/plan-reply image; verify it still lands.

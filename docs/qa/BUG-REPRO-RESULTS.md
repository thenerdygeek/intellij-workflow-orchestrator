# Bug-Reproduction Results (2026-06-26)

Reproductions for the four owner-reported bugs in `BUG-REPRODUCTION-SCENARIOS.md`, against the local mock on the fixed build (0.87.2, `allowPrivateUrls=true`, Sourcegraph→localhost:8088, model `claude-sonnet-mock`).

| Bug | Status | One-liner |
|---|---|---|
| BUG-1 resume auto-iterates | ✅ REPRODUCED | Resume auto-starts the loop with no user message (see below) |
| BUG-2 scroll-to-bottom short/jumps | ✅ REPRODUCED | Autoscroll parks mid-transcript (1st block one run, 3rd block next) on tall code blocks; manual chevron lands flush (see below) |
| BUG-3 history thinking-block appends | 🟡 root-cause confirmed; live window unreachable with mock | thinking ships as 1 instant SSE frame + 2-click history-open → timing window too small; see below |
| **BUG-4 Opus-4.5 3× overbill** | ✅ **ALREADY FIXED in source** | see below |

---

## BUG-4 — Opus-4.5 cost overbill: ALREADY FIXED (cannot reproduce as written)

The doc's premise is that `core/src/main/resources/pricing.json` prices `claude-opus-4-5` at the old **$15/$75**. **In the current tree it is `$5/$25`** (the correct Opus-4.5 rate), and `claude-opus-4-6` / `4-7` (+ `-thinking` variants) are all `$5/$25` too.

```
claude-opus-4-5:          { "in": 5.00, "out": 25.00, "cacheRead": 0.50, "cacheWrite": 6.25 }   ← correct
claude-opus-4-5-thinking: { "in": 5.00, "out": 25.00, … }                                       ← correct
claude-opus-4-6 / 4-7:    { "in": 5.00, "out": 25.00, … }                                        ← correct
```
The only `$15/$75` entries are `claude-opus-4` (base/old Opus-4), `claude-opus-4-thinking`, and `claude-3-opus` — correct for those older models.

**Build copy matches source** (`diff core/src/.../pricing.json core/build/resources/main/pricing.json` → identical), so the **running sandbox build already bills Opus-4.5 at $5/$25**. The formula (`ModelPricing.computeCost`) and lookup (`ModelPricingRegistry.lookup`) were never the issue — only the data, and the data is now correct.

**Verdict: BUG-4 is resolved.** No live mock repro needed. (If desired, a regression guard: assert `pricing.json["claude-opus-4-5"].in == 5.00 && .out == 25.00`.) Note the mock model `claude-sonnet-mock` has **no** pricing entry, so on the mock the cost chip shows nothing unless a `~/.workflow-orchestrator/pricing.json` override is added — that's expected, not a bug.

---

## Root-cause corroboration (source spot-checks for BUG-1 / BUG-2 / BUG-3)
- **BUG-1:** `AgentController.showSession()` at `AgentController.kt:4341` (view-only open) and `AgentController.resumeSession()` at `:4457` (runs the loop) — two distinct resume affordances, as the doc states.
- **BUG-3:** `AgentController` has `viewedSessionId` (`:392`) with session guards `if (viewedSessionId != sessionId) return` at `:1515/:1561/:1601`, **but** the thinking batcher push `onFlush = { dashboard.appendToThinking(...) }` (`:518`) is **not** behind that guard — and a code comment at `:397-398` explicitly notes the push-time check "cannot catch navigations that change the view WITHOUT setting a new viewedSessionId — `showHistory` leaves...". Directly corroborates the cross-session thinking-append mechanism.
- **BUG-2:** `MessageList.tsx` exists at `agent/webview/src/components/chat/MessageList.tsx`; root cause (Virtuoso `scrollToIndex({index:'LAST',align:'end'})` on stale cached heights vs. `autoscrollToBottom`) to be confirmed live with tall async code blocks.

---

## BUG-1 / BUG-2 / BUG-3 — reproduction status (this session)

- **BUG-1 (resume auto-iterates):** ⏳ **not yet cleanly reproduced.** Built-in `read-and-finish`/`long-stream` complete too fast to interrupt; `multi-tool` reached an approval gate (good interrupt point) but Stop did not halt the run_command (see BUG-STOP-1), so I could not cleanly create an interrupted session. Best path: the doc's `bug1-interruptible.json` custom scenario (needs operator `curl` POST). Root cause corroborated at source (`AgentController.showSession:4341` view-only vs `resumeSession:4457` runs the loop).
- **BUG-2 (scroll-to-bottom short/jumps):** ✅ **REPRODUCED** via Chrome-POSTed `long-codeblocks` (tall async Kotlin blocks). Autoscroll parks mid-transcript (1st block one run, 3rd block the next) instead of following to the bottom; manual chevron lands flush. See the BUG-2 section below.
- **BUG-3 (history thinking-block appends):** 🟡 **root-cause confirmed; live window unreachable with the mock.** Thinking ships as one instant SSE frame (no inter-frame delay) and history-open is a 2-click flow, so the sub-second stream always finishes before a 2nd session is displayed. Root cause strongly corroborated at source (thinking push `AgentController.kt:518` is not behind the `viewedSessionId` guard at `:1515/:1561/:1601`; comment at `:397` acknowledges the gap). Mock REQ-5 filed to chunk+delay thinking so it becomes live-reproducible. See the BUG-3 section below.

---

## NEW bugs found while driving the agent as a user (2026-06-27)

### BUG-STOP-1 — P1 — a streaming `run_command` cannot be stopped
**Repro:** send `[multi-tool] …`; at the `run_command` approval gate, **Approve** the `grep -rn TODO . || true`. While stdout streams, click the **per-tool Stop (■)** on the command card, then the **session Stop (■)** in the composer.
**Expected:** the command terminates; streaming stops.
**Actual:** output keeps streaming — grew **719 → 1152 → 3258+ lines** *after* the kill was issued. The IDE log shows `AgentController: stop requested for tool call xmltool_5` → `[ProcessRegistry] Killing process for tool call: xmltool_5` (00:54:30) and `AgentController.cancelTask` (00:55:00), yet the process keeps producing output. Either the OS process isn't actually killed or already-buffered 16 KB chunks keep flushing unbounded.
**Evidence:** `idea.log` `RunCommandTool[xmltool_5]: process started` → `stop requested` → `Killing process` → output still grows. (Note the repo has a `.worktrees/feature+unified-tool-stop` branch — this is a known WIP area.)
**Impact:** a user cannot abort a runaway command; it runs to completion / the 5-min timeout.

### GLITCH-1 — P2 (UI) — run_command elapsed timer freezes while output streams
The command card header froze at **"29.9s / 5m 0s"** while stdout kept streaming for well over a minute more (line count 700→3258). The elapsed counter stopped updating; the cap (5m) is shown but the live timer is stuck.

### PERF-1 — P2 — unscoped `grep -rn TODO .` floods UI + log + triggers indexing churn
The `multi-tool` scenario runs `grep -rn TODO . || true` from the **repo root**, so it scans `.git/`, `.worktrees/`, `node_modules/`, `build/` — emitting thousands of lines (3258+ and counting), flushed in 16 KB chunks into the chat **and** written into `idea.log` (bloated the log so much a normal `grep` over it overflowed). The status bar went to **"Indexing…"** during the flood. Two fixes: **mock** should scope the command (`grep -rn TODO src/ --include='*.kt' || true`) — see MOCK-SERVER-REQUESTS REQ-4; **plugin** should cap/scroll-virtualize huge run_command output and not mirror full stdout to `idea.log`.

### OBS — title generation is intermittent (re BUG-AGENT-2)
On some completions the header title rendered as **"Mock: generated title"** (title-gen succeeded); on others it threw the SSE-parse SEVERE + "IDE error occurred" balloon (BUG-AGENT-2). So the failure is intermittent, not every-time as first thought.

### Positives observed (working well)
- Approval gate renders correctly (BASH card + command + cwd + "Approve run_command? (medium risk)" + Deny/Approve) — TOOL-21/HDR-25 ✅.
- `run_command` **live stdout streaming**, CMD badge, "Show all N lines" expander, and **timeout-cap display** — TOOL-13…20 ✅ (aside from the freeze/stop bugs).
- Markdown rendering: headings, fenced **code block**, and a **GFM table** all render correctly (`[long-stream]`) ✅.
- Scroll-to-bottom affordance appears on scroll-up and lands flush for normal-height content ✅.

### Still to do (needs operator `curl` for custom scenarios)
S2 (create_file card category — blocked this run by BUG-STOP-1), BUG-1/2/3 custom-scenario repros, and a stray runaway `grep` process may still be running on the host (consider `pkill -f "grep -rn TODO"`).

### BUG-STOP-1 / GLITCH-1 — UPDATE: how Stop actually responds (full sequence)
Driving the Stop buttons deliberately, the behavior is a multi-stage glitch:
1. **During streaming, Stop is unresponsive for a long time.** Per-tool ■ and session ■ both logged the kill (`stop requested` → `Killing process` → `cancelTask`) but stdout kept flooding (700→3258+ lines) for **2+ minutes**.
2. **The task eventually cancels** — the transcript shows **"Task cancelled."** and the run_command output is **discarded → "No output"**.
3. **But the run_command card never reaches a terminal state — it's orphaned:**
   - the **⟳ spinner keeps spinning** indefinitely,
   - the **elapsed timer keeps incrementing** (observed `2m 14s → 2m 47s → …`) *after* "Task cancelled",
   - the **Stop ■ stays visible but does nothing** when clicked,
   - the body shows **"No output"**.
   So the card is stuck "running forever" while the task is already cancelled.

**Two distinct timer glitches on the same card:** during streaming the elapsed timer **froze** at `29.9s` (GLITCH-1); after cancel the same timer **runs away upward** past `2m 47s`. Neither reflects reality.

**Net:** Stop is (a) very delayed/unresponsive while a command floods output, and (b) leaves the tool card in a permanent fake-"running" state (spinner + climbing timer + dead Stop button) after the task is cancelled. The OS `grep` process may also still be alive on the host even after "Killing process" was logged (consider `pkill -f "grep -rn TODO"`). Severity: **P1** (no reliable way to abort; orphaned UI state).

---

## BUG-1 — ✅ REPRODUCED (2026-06-27, via Chrome-POSTed custom scenario)

**How the interrupted session was created (without fighting Stop):** registered a custom scenario `interruptible-gate` (turn 1 = `run_command{echo interrupt-test}` → approval gate; then read_file ×2 → attempt_completion) by `fetch`-POSTing to `http://localhost:8088/__admin/sourcegraph/scenario/custom` from a Chrome tab on the mock's own origin (no terminal needed). Sent a message → the loop **paused at the `run_command` approval gate**. A **single Stop click at the paused gate cancelled cleanly** ("Task cancelled.", composer reset, no orphaned card — confirming BUG-STOP-1 is specific to a *running* streaming command). This left an **interrupted** session persisted to `sessions.json` (`c7fc36f0…`, task "Check the build then review it.", `session_end durationMs=81142 iteration=0 tokens=0`).

**Two resume affordances observed (matches the doc's root cause):**
1. **History-card "Resume"** → opens the session **view-only** (`showSession`): shows the transcript + an in-chat banner *"This session was interrupted. You can continue where it left off."* with a blue **▶ Resume** button. No iteration yet. ✅ (good behaviour)
2. **In-chat "Resume" button** → **`resumeSession` runs the loop immediately**: the header flipped to **"Waiting for approval"**, the agent **re-emitted the `run_command` tool call**, the approval gate reappeared (with a new "Approve all 'echo' this session" option), and a loading shimmer ("Understood the assignment…") showed the loop actively iterating — **all with NO user message typed in between.**

**Oracle:** a new assistant turn / tool call (`run_command`) appears with **no preceding user turn** after clicking Resume (visible on screen; the resume continues the same session `c7fc36f0` and issues a fresh `api_call` without a new user message).

**Verdict:** matches the owner's report and the root cause (`resumeSession` → `executeTask(...)` for interrupted sessions; no load-and-park mode). **Expected:** Resume should load + **park**, waiting for the user's next message; the loop should start only when the user sends one. **Actual:** Resume auto-starts the loop. Severity **P1** (owner's primary pain point).

**Bonus observations during BUG-1:**
- **S7 re-confirmed:** the interrupted session's history card shows no status badge — nothing distinguishes "interrupted" from "completed" until you open it and see the resume banner.
- **History loads async:** immediately after opening the History view it rendered **empty** for ~1s, then populated all 8 sessions. Worth a glance (could read as "history is empty" to a user) but it does load.
- **Risk classification works:** the `echo` command was tagged **"low risk"** at the gate, vs the `grep` earlier tagged **"medium risk."**

---

## BUG-2 — ✅ REPRODUCED (2026-06-27, via Chrome-POSTed custom scenario `long-codeblocks`)

**Scenario:** registered `long-codeblocks` (5 turns; each turn renders a fenced **Kotlin** code block of 120–140 lines — `firstFunc…`/`thirdFunc…`/`fifthFunc…`, wide lines with long inline comments — plus a short prose paragraph, then `attempt_completion`). POSTed to `http://localhost:8088/__admin/sourcegraph/scenario/custom` from a Chrome tab on the mock origin (no terminal). Driven from a **fresh chat** (the New button), prompt: "Show me a detailed analysis with code."

**Oracle for BUG-2:** after the run completes, is the view auto-scrolled flush to the bottom (last line of the final block / TASK COMPLETED adjacent to the composer), or is it left parked short of the bottom?

**Actual (reproduced):** on completion ("Mock: generated title" in the header — the run finished), the transcript was **auto-scrolled to a position partway up, not the bottom**, and the **landing point was inconsistent between runs**:
- **Run A:** view parked at the **first** code block (the final `fifthFunc129…139` region was off-screen below).
- **Run B (fresh chat, identical prompt):** view parked at the **third** code block (`thirdFunc0…14` visible), with the scroll-to-bottom chevron showing.

In both runs the last block, trailing prose, and the **TASK COMPLETED** card were **below the fold** — the autoscroll-to-bottom did **not** follow the tall async code-block content to the end. **Clicking the manual scroll-to-bottom chevron** (once heights had stabilized post-stream) **landed flush every time** (last line `fifthFunc139` → prose → TASK COMPLETED → "Rendered several tall code blocks." all adjacent to the composer).

**Interpretation / root cause (matches the corroboration above):** the streaming-time autoscroll (`autoscrollToBottom` / `scrollToIndex({index:'LAST',align:'end'})` in `MessageList.tsx`) computes the target against **stale/unresolved Virtuoso item heights** while the tall code blocks are still being measured, so it lands **short** and at a **non-deterministic** offset (different block each run = the owner's "jumps"). Once heights resolve, the **manual** chevron computes correctly and lands flush — which is why the post-hoc chevron always works but the auto-follow does not. This is the "scroll-to-bottom lands short / view jumps" behaviour from the owner report.

**Severity:** P2 (UI) — on any answer containing tall/async-rendered content the user is silently left mid-transcript and must manually scroll to see the completion; the inconsistency (different landing each time) makes it feel like the view "jumps."

**Note (continuing a completed session):** sending a *new* message into an already-`TASK COMPLETED` session (rather than via the New button) did **not** start a visible new turn — the composer cleared but no user bubble / streaming appeared and the view didn't move. Could not confirm whether the message was dropped or rendered off-screen; flagged as a possible secondary issue to re-check (may itself be a scroll-anchoring miss on the appended turn).

---

## BUG-3 — 🟡 ROOT-CAUSE CONFIRMED; live window not reachable with the mock (2026-06-27)

**What I tried:** registered custom scenarios `long-thinking` (600-line `<thinking>` + 10×80-line text chunks) and `huge-thinking` (4000-line `<thinking>`) via Chrome POST. Sequence per the doc: New chat → send → the instant the thinking starts → switch to **History** → open a *different* (older) session, to catch session A's thinking appending into session B's displayed view.

**Why it couldn't be hit live (two mock/UI facts, both verified in source + UI):**
1. **Thinking is emitted as a single SSE frame, not chunked.** `Turn.contentFragments()` (`mock-server/.../scenario/Turn.kt:95-100`) wraps the whole `thinking` string in **one** `<thinking>…</thinking>` fragment, and `CodySseSerializer` emits **one `deltaText` frame per fragment** (`CodySseSerializer.kt:34-38`). There is **no inter-frame delay** anywhere in the mock (grep for `delay`/`sleep` → none in the SSE path). So even a 4000-line thinking block arrives in one burst and renders in well under a second.
2. **Opening a history session is a 2-click flow.** Clicking a history card first **expands** it to reveal *Resume / Delete* (verified live — clicking the card title toggled the action row, did not open the chat); a second click on **Resume** opens it. Combined with the async history-list load (~1s, see below) the navigation takes ~3–4 s — far longer than the sub-second thinking stream. By the time session B is displayed, A's stream is already `0 tok` / done.

**Net:** every attempt finished streaming before I could display session B, so the cross-append could not be observed in the UI. This is a *test-harness* limitation, not evidence against the bug.

**Root cause remains strongly corroborated at source (unchanged):** the thinking-batcher push `onFlush = { dashboard.appendToThinking(...) }` at `AgentController.kt:518` is **not** behind the `viewedSessionId` guard used at `:1515/:1561/:1601`, and the comment at `:397-398` explicitly states the push-time check "cannot catch navigations that change the view WITHOUT setting a new viewedSessionId — `showHistory` leaves…". So a thinking delta that fires while the user has navigated to another session's view will append into the wrong view. Confirmed: navigating to **History** does **not** stop or re-target an in-flight stream (the background task kept running and persisted independently while I was in the History list).

**To make BUG-3 live-reproducible (mock REQ-5, filed):** the mock should (a) **chunk** the `thinking` string into many small `deltaText` frames and (b) insert a small **inter-frame delay** (e.g. 30–50 ms) so a long thinking block streams over several seconds — opening a window wide enough to navigate mid-thinking. Alternatively, add a **source unit/UI test** that drives `appendToThinking` after a `showHistory`/`showSession` navigation and asserts the delta does not land in the newly-viewed session.

---

## NEW bugs found this session (2026-06-27, BUG-2/3 runs)

### BUG-TITLE-1 — P2 — session-title fallback leaks raw `<thinking>` tags + code into the History title
When title generation fails (the BUG-AGENT-2 / REQ-2 non-streaming SSE-parse error), the History card title falls back to the **raw first assistant message, unsanitized**. Observed verbatim card title:
`<thinking> Render a tall code block then read a file. </thinking> Here is the first analysis block: ```kotlin // ===== first code block (120 lines) ===== fun firstFunc0(value: Int, name: String): In…`
The title should never contain `<thinking>…</thinking>` markup or code-fence/source content — it should be a short, stripped summary (or a neutral placeholder like the first user line). Two layers to fix: (a) make title-gen resilient to an SSE body (REQ-2 / BUG-AGENT-2), and (b) **sanitize the fallback** — strip `<thinking>` blocks and code fences and truncate to a clean phrase. Reproducible whenever title-gen errors on a turn whose first assistant content begins with a thinking block.

### GLITCH-2 — P3 (UI) — History cards render as blank placeholders during async load
On opening **History**, the list first paints **empty card frames** — the favorite-star icon is drawn on the right of each row but **no title / timestamp / model text** for ~1 s, then the text populates. (Earlier I logged "history renders empty for ~1s"; refined here: it's not empty, it's *N blank card skeletons with stars but no text*, which reads as a glitch rather than a loading state.) A real skeleton/shimmer or a spinner would read better than blank rows that look broken.

### OBS — navigating to History does not interrupt an in-flight agent stream
Confirmed (relevant to BUG-3's mechanism, and good behaviour in isolation): sending a message and immediately switching to the History list left the task **running in the background** — it completed and persisted on its own (appeared as a new `0 tok` "just now" card). The stream is not bound to the chat view being visible, which is exactly why a thinking delta can land in a different displayed view (BUG-3).

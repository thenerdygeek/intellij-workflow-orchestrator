# Behavioral & Visual Test Plan — Workflow Orchestrator Plugin

> **Audience:** a tester (a Claude "cowork" agent **or** a human) driving the plugin via `./gradlew runIde`
> on a **licensed Windows IntelliJ Ultimate** box, with Sourcegraph + connector tokens configured.
> **Goal:** exercise *every visible and interactive surface* and catch **visual + behavioral** bugs —
> does a number update correctly, does scrolling stick to the bottom, does a card expand, is the
> right info visible per tool, can an existing chat be resumed and continued.

This plan is the **behavioral** counterpart to the existing **log-oracle** catalogs
(`RUNIDE-TEST-SCENARIOS.md`, `.superpowers/runide-catalog/`, `WINDOWS-RUNIDE-CHECKLIST.md`).
Those tell you *what a failure looks like in `idea.log`*; this one tells you *what to click and
what "correct" looks like on screen*. Use them together: when a scenario here fails, grab the
matching marker/log from the oracle docs and attach it.

- **283 scenarios** across **8 sections**, **1,155 tickable checks.**
- Every scenario is **source-grounded** (`file:line` citations); nothing invented.
- Authored from source on macOS (this dev box cannot `runIde` — Ultimate license wall). **Every
  scenario is therefore *unconfirmed against a live IDE* and must be physically run on Windows.**

---

## 0. How to use this plan

1. Read **§1 (read-only rule)**, **§2 (conventions)**, and **§3 (how to report a bug)** below — they
   apply to every scenario.
2. **Start with §4 — "Suspected bugs found while authoring."** These were spotted statically in the
   source and are the highest-ROI things to confirm. If they reproduce, you have bugs before you've
   even finished the happy path.
3. Work through the sections in **§5**. Each scenario is self-contained: Preconditions → Steps →
   Expected (visual) → Expected (behavioral) → ✅ Checks → 🐞 Bug signals → theme/size matrix.
4. Tick each `- [ ]` check. A scenario **passes** only when *all* its checks pass in *both* themes
   (and at narrow + wide width where the scenario says so).
5. File anything that fails (or any 🐞 bug-signal you observe) using the report format in §3.

> **A "bug" is not only a crash.** A stale number, a scroll that jumps, a card that won't expand, a
> badge with the wrong color, text that overflows its row, a value that doesn't survive a restart,
> unreadable contrast in dark mode — all are reportable. The ✅ checks and 🐞 signals name the
> specific ones to watch per scenario.

---

## 1. ⛔ READ-ONLY — the one hard rule

Per `docs/superpowers/specs/2026-06-25-runide-smoke-test-automation-handoff.md §3` and
`WINDOWS-RUNIDE-CHECKLIST.md`: **perform NO write against any backend.**

| Backend | Forbidden (⛔ verify the confirm dialog, then **Cancel**) |
|---|---|
| **Jira** | create/transition a ticket, log time, post a comment, "Start Work" (branch create), Jira closure |
| **Bitbucket** | merge / approve / decline a PR, post a comment, push an AI review, create a PR, edit description |
| **Bamboo** | trigger / rerun / stop a build, queue automation |
| **Sonar** | (read-only by nature) |
| **Sourcegraph / Agent** | run the agent **only against a throwaway scratch file**; `run_command` only with **read-only** commands; never let a write-tool touch real project files |
| **Handover** | Jira closure, copyright fix-all, any state write |

Where a scenario reaches a write control, its **⛔ Write note** tells you to **confirm the dialog
appears and is correct, then Cancel**. Verifying the *dialog* is itself a valid test; executing the
*write* is not.

**Allowed local writes:** entering URLs/tokens in Settings (PasswordSafe), copy-to-clipboard chips,
deleting a *local* agent session from history (confirm the dialog first), and editing **one scratch
file** via the agent.

---

## 2. Global conventions

**Theme matrix.** Every scenario must be checked in **IDE Light *and* Dark** (Settings → Appearance
→ Theme, or the dark/light toggle). Watch for stale colors, unreadable contrast, and icons that
don't swap their light/dark SVG variant. Scenarios that are layout-sensitive also say to check
**narrow vs wide** tool-window width and **long/overflowing** content.

**Number-correctness discipline (the user's #1 ask).** When a scenario checks a count / token / % /
cost / duration, don't just confirm it *moves* — confirm it's *right*:
- Agent token & context %: cross-check against the session JSONL
  (`~/.workflow-orchestrator/{proj}-{sha6}/logs/agent-YYYY-MM-DD.jsonl`,
  `jq 'select(.event=="api_call")|{promptTokens,completionTokens}'`) and
  `sessions/{id}/api_conversation_history.json`.
- Connector counts (e.g. "(N tickets)", "N PRs"): N must equal the visible rows.

**Scroll discipline.** "Sticks to bottom while streaming," "releases when you scroll up,"
"scroll-to-bottom affordance appears," "no jump when content is appended below the fold," "position
restored on session switch" — each is a separate, observable check; don't conflate them.

**Setup checklist (do once):**
1. `git pull`, activate Ultimate (or 30-day trial), `./gradlew runIde`, open a real project.
2. Settings → Tools → Workflow Orchestrator → Connections: enter URLs + tokens for Jira, Bamboo,
   Bitbucket, Sonar, Sourcegraph; **Apply**; confirm each shows verified/connected.
3. Create a **scratch file** (e.g. `SCRATCH.md`) for agent write-tool scenarios.
4. Confirm the diagnostic log is on (Telemetry settings) — you'll attach it on failures.

---

## 3. How to report a bug

For each failure, capture:

```
[<SCENARIO-ID>] <one-line symptom>
Theme: light | dark        Width: narrow | wide
Expected: <what the scenario said should happen>
Actual:   <what happened>
Repro:    <the exact steps / which check # failed>
Logs:     <attach plugin-0.log; for agent failures also the dated agent-*.jsonl>
Screenshot/GIF: <attach — especially for visual/scroll/theme bugs>
```

**Which log to attach** (from `WINDOWS-RUNIDE-CHECKLIST.md §1`):
- **Always:** `%USERPROFILE%\.workflow-orchestrator\diagnostics\plugin-0.log` (small, plugin-only).
- **Agent failures, also:** `%USERPROFILE%\.workflow-orchestrator\{proj}-{sha6}\logs\agent-YYYY-MM-DD.jsonl`.
- For the **exact marker** to grep per area, see `RUNIDE-TEST-SCENARIOS.md` (it lists the success
  *and* failure log signature for every connector + agent path).

**Severity guide:** P0 = crash / data loss / can't load. P1 = a core action is broken or a number is
wrong. P2 = visual/layout/contrast/scroll-jank. P3 = polish.

---

## 4. ⭐ Suspected bugs found while authoring (verify these FIRST)

These were spotted **statically** by reading the source — they have **not** been confirmed on a live
IDE. Each is the highest-ROI thing to check in its area: confirm whether the symptom reproduces in
`runIde`, then file or dismiss. Citations are to the source as read on `feature/plugin-split`.

> **A second reviewer fact-checked this table against source.** Of the original 14, **12 are
> confirmed**, **S12 was refuted** (its symptom can't occur — see below), and **S14 was corrected**
> (the widget *does* exist). S13 is real but **by design**. Verdicts are noted per row.

| # | Area | Suspected issue | Evidence (source) | Confirm via |
|---|---|---|---|---|
| **S1** | Agent / tool cards | `imageRefs` "N images attached from tool" badge is **plumbed end-to-end but never rendered** — no component reads `tc.imageRefs`, though `types.ts` documents the badge. | `bridge/types.ts:60-65` documents it; no read in `ToolCallChain.tsx`/`ToolCallDetails` | TOOL section; drive a tool that returns an image |
| **S2** | Agent / tool cards | `create_file` (and `delete_file`) are **absent from `CATEGORY_MAP`** → they fall to the grey `TOOL` category with plain input/output text instead of the `WRITE` badge + `DiffHtml` diff that `edit_file` gets. The map lists `write_file`/`Write`, but the real core tool is `create_file`. | `ToolCallChain.tsx` `CATEGORY_MAP` vs core tool names | TOOL section; have the agent create the scratch file |
| **S3** ✅confirmed | Agent / input | `UsageIndicator` (below-input token bar) appears **not mounted in the live app** — referenced only by `HarnessApp.tsx` (+ its test); `App.tsx:107-133` and `InputBar.tsx` never render it. | grep: only `HarnessApp.tsx` imports it | IN-29 / HDR-7 |
| **S4** | Agent / input | `ContextChip.tsx` and `ActionToolbar.tsx` appear **showcase-only** (live composer uses inline RichInput chips + `ChipPreview`). | imported only by `showcase.tsx` | confirm they're absent from live composer |
| **S5** | Agent / header | `SessionStatsChips` shows **only tokens + cost — not duration/iteration**, even though `SessionInfo.iterations/durationMs` is stored by `completeSession`. | no component reads `s.session` | HDR-15 |
| **S6** | Agent / scroll | **Per-session scroll position is not restored** on session switch — `MessageList` has no scroll-offset persistence; `hydrateFromUiMessages` replaces messages wholesale. | no `restoreStateFrom`/`initialTopMostItemIndex` in `MessageList` | MSG-27 |
| **S7** | Agent / history | History cards render **no status badge** (running/completed/interrupted/error) — `HistoryItem` has no status field; status only manifests as resume-bar gating when you open a session. | `SessionCard.tsx`, `HistoryItem` shape | HIS section |
| **S8** | Agent / history | **No favorites filter** exists, despite a star toggle on cards. | `HistoryView.tsx` | HIS section |
| **S9** | Agent / history | **Context-menu Delete has no confirmation**, while the inline-card delete and bulk delete both do. | `SessionContextMenu.tsx` vs `SessionCard` inline + bulk path | HIS section — ⚠ this *does* delete a local session; confirm the (missing) dialog, expect immediate deletion |
| **S10** | Agent / history | Context menu positions with raw `clientX/clientY` and **no viewport clamping** → can clip off-screen near edges. | `SessionContextMenu.tsx` | HIS section; right-click a card near the panel's bottom/right edge |
| **S11** | Agent / resume | The startup interrupted-session affordance is a **notification balloon, not an in-chat banner**; there's **no auto-resume** and a **10-minute recency gate** (older interrupts won't prompt). | `AgentStartupActivity`, resume gating | HIS-12…HIS-17 |
| **S12** ❌REFUTED | Agent / header | ~~Top meter vs bottom indicator disagree after an idle manual compaction.~~ **Not reproducible:** the "bottom indicator" *is* `UsageIndicator`, which S3 shows is **never mounted** — so there is no second on-screen meter to disagree with. The dual refresh paths are real in code but never co-render. Do not hunt this. | folds into S3 | — (skip) |
| **S13** ⚠by-design | Connectors / Jira | `PermissionGate.canTransition` is **fail-open**: if `getMyPermissions()` errors, all transition buttons stay **enabled** even though the server may reject the write. This is **documented intentional** (`PermissionGate.kt:16-21`, `PERMISSIVE=PermissionGate(null)` `:33`, `jira/CLAUDE.md`). Verify the **UX consequence** (a button that looks usable but the server rejects) — do **not** file as an oversight. | `PermissionGate.kt:16-21,33` | SPR-13 (observe button state; don't execute) |
| **S14** ✏️corrected | Core | A status-bar widget **does** exist — `AutomationStatusBarWidgetFactory` (`:automation`), registered in the root `plugin.xml:356-357` as **"Workflow Automation Queue"** (shows `✓ Suite Idle`). None lives in `:core`; `jira/CLAUDE.md`'s "TicketStatusBarWidget" is **stale** (no such source). **Not a bug** — but verify the Automation widget renders + its click/tooltip behaves. | `plugin.xml:356-357`, `AutomationStatusBarWidgetFactory.kt` | CORE section + Automation widget |

**Confirmed *not* a bug (don't file):**
- Handover `build.url` placeholder always renders "—" — `BuildSummary` has no URL field *by design*
  (`handover/CLAUDE.md:65`, `HandoverModels.kt:48-52`). HND-07.
- Handover has **no** AI pre-review card — it was removed; PR-tab `AiReviewTabPanel` owns pre-review.
  If you see an AI pre-review card in the Handover tab, *that* is unexpected → report it.
- The two context indicators use **intentionally different** color thresholds (meter 80/88/97
  `TopBar.tsx:39-41` vs UsageIndicator 50/80 `UsageIndicator.tsx:68`). Note: only the **top meter**
  is actually rendered (the UsageIndicator is unmounted — S3), so the live "same numbers" cross-check
  in HDR-7 can't be performed; just confirm the top meter's numbers are correct.

---

## 5. Sections

| # | File | Scenarios | Focus |
|---|---|---|---|
| 01 | [sections/01-agent-chat-input-composer.md](sections/01-agent-chat-input-composer.md) | `IN-1…31` (31) | Input bar, RichInput undo, @-mentions, /-skills, #-tickets, attachments, drag-drop, model/plan chips, steering/queue, usage indicator |
| 02 | [sections/02-agent-message-stream-rendering.md](sections/02-agent-message-stream-rendering.md) | `MSG-1…31` (31) | Streaming append, markdown, **code-block copy + syntax**, link confirm, thinking/reasoning, **scrolling (stick/release/scroll-to-bottom)**, toasts |
| 03 | [sections/03-agent-tool-cards-rich-output.md](sections/03-agent-tool-cards-rich-output.md) | `TOOL-1…43` (43) | **Per-tool card info + expand/collapse**, run_command streaming + ANSI + timeout cap, diff preview + stats, rich renderers (tables/charts/mermaid/images), completion/async cards |
| 04 | [sections/04-agent-header-context-controls.md](sections/04-agent-header-context-controls.md) | `HDR-1…37` (37) | **Context/token meter correctness + compaction reset**, session stats, model selector, plan mode → Approve→Act, approval gate, question/stdin, stop/background |
| 05 | [sections/05-agent-history-sessions-subagents.md](sections/05-agent-history-sessions-subagents.md) | `HIS-1…32` (32) | History list, **resume + continue chatting (marquee)**, new/delete/favorite, search, sub-agents (≤5 parallel), cross-IDE delegation |
| 06 | [sections/06-connectors-sprint-pr.md](sections/06-connectors-sprint-pr.md) | `SPR-1…20`, `PRT-1…20` (40) | Sprint (Jira): ticket list/detail, transitions ⛔. PR (Bitbucket): list, detail tabs, diff, comments, AI review, merge ⛔ |
| 07 | [sections/07-connectors-build-quality-automation-handover.md](sections/07-connectors-build-quality-automation-handover.md) | `BLD/QAL/AUT/HND` (37) | Build (Bamboo) stages/logs, Quality (Sonar) coverage/issues, Automation suite/baseline, Handover checks/copyright/clipboard |
| 08 | [sections/08-core-toolwindow-settings-insights.md](sections/08-core-toolwindow-settings-insights.md) | `CORE-1…32` (32) | Tool window + 7 tabs, empty states, Settings (5 pages) + token persist, Insights (Today/Week/Sessions/Reliability), onboarding, gutter markers, **theme repaint** |

---

## 6. Coverage map & what is *not* covered here

**Covered:** the Agent chat webview (input, stream, tool cards, header/meter, history/resume,
sub-agents), all 6 connector tabs + the Agent tab, Settings, Insights, onboarding, gutter markers,
theming.

**Out of scope (covered elsewhere or irreducibly manual):**
- **Plugin-split A/B classloader wiring** → `RUNIDE-TEST-SCENARIOS.md` P0-2/P0-3 + `PHASE-0A-SMOKE-TESTS.md`.
- **Token-persist log oracle** → `RUNIDE-TEST-SCENARIOS.md` P0-4 (this plan covers the Settings UI side, CORE-10…15).
- **Backend write paths** (merge, transition, trigger, closure, fix-all) — ⛔ read-only run; dialog-only here.
- **Cross-IDE delegation end-to-end** — needs two consenting IDE instances; single-box scenarios are observe-only.
- **JCEF/Chromium raw rendering, native keychain, Swing paint** — see the handoff doc's "irreducibly manual" list.

---

## 7. ⛔ Write-op cheat sheet (STOP points — read before each session)

Every scenario below reaches a control that **writes to a backend**. The tester **stops at the
dialog / button** — verify it appears and is correct, then **Cancel**. **Never execute the write.**
Two have **NO confirmation dialog** — be extra careful not to click them.

| Scenario | Write op | Safe stop |
|---|---|---|
| **PRT-13** ⚠ **NO DIALOG** | PR **Approve / Needs Work** — fires the Bitbucket API immediately on click | Read the button label only; do **not** hover-then-click. Zero recovery. |
| **HND-05** ⚠ **NO DIALOG** | Copyright **Fix All** — rewrites source files via `WriteCommandAction`, no prompt | Read the affected-file list + count; **stop before the Fix All button**. |
| SPR-13 | Jira transition ticket | Verify the transition dialog, Cancel |
| SPR-15 | Jira quick comment (post) | Verify the input bar; do not Post |
| SPR-16 | "Start Work" (branch create) | Verify the dialog, Cancel |
| PRT-7 | Edit PR description / AI-enhance (saves) | Verify edit mode; do not Save |
| PRT-8 / PRT-11 | PR activity reply / comment post / toggle-resolved | Verify the composer; do not Post |
| PRT-12 | AI Review **push** to Bitbucket | Verify the review renders; do not Push |
| PRT-14 | PR **Merge** (Merge Options dialog) | Verify the dialog, Cancel |
| PRT-15 | PR **Decline** (confirm dialog) | Verify the dialog, Cancel |
| PRT-17 | **Create PR** dialog | Verify the dialog, Cancel |
| BLD-13 | **Stop / Cancel / Rerun** build | Verify each confirm dialog, Cancel |
| AUT-06 | **Trigger** (Customized / Trigger All Stages) — enqueues immediately | Verify the payload preview; do **not** trigger (or use a designated safe plan key + immediately Remove from Monitor) |
| HND-04 | Jira "comment posted" / "time logged" toggles | Do not perform those writes |
| HND-07 | **Post to Jira** (closure comment) | Verify the rendered template; do not Post |
| Agent write-tools | `edit_file` / `create_file` / `run_command` | **Scratch file / read-only commands only** — never real project files |

> **External build trigger (BLD-10):** to see the "newer build running" banner you must trigger a
> build **from the Bamboo web UI in a browser**, NOT the in-plugin Trigger button. Treat that as an
> external prerequisite, not an in-plugin action.

---

> Reminder: this whole plan was authored from source on a license-blocked Mac. Treat §4 as
> *hypotheses to confirm* and every section as *unrun until a Windows pass ticks the boxes*.

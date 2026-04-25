# Phase 4 — parked prongs (B / D-profile / E)

**Status:** parked, awaiting live-IDE profiling capacity
**Branch:** `refactor/cleanup-perf-caching`
**Date parked:** 2026-04-25
**Phase 4 prongs completed:** A (5 EDT-freeze fixes), C (10 commits — coroutine scope tightening)
**Phase 4 prongs deferred:** B (EDT hotspots), D-profile (PSI batching for hot paths), E (JCEF rendering)

---

## Why parked

Phase 4's profile-driven prongs (B and E, plus the profile-portion of D) require running the plugin in a live IntelliJ instance, capturing flame graphs / DevTools traces during real interactions, and shipping per-commit evidence ("p50 X ms → Y ms" or "Long Tasks: none > 16 ms"). The current development environment cannot run `./gradlew :runIde` and exercise the UI manually.

Rather than blind-fix likely hotspots (which violates `intellij-plugin-performance` SKILL.md §0 — "Measure before you touch code"), these prongs are parked with an explicit resumption protocol below. When live-IDE capacity is available — your own dev session or a future agent in an environment that supports it — the protocol is enough to pick up cold and finish.

The correctness-only prongs (A, C, A.2, D-grep) covered everything that could be verified without a live IDE. They are complete:

- **Prong A (correctness)** — 5 EDT freezes in AgentController. Done. See `phase4-prong-a-plan.md`.
- **Prong C (correctness)** — 10 commits, scope-leak elimination across 58 audited sites. Done. See `phase4-prong-c-plan.md` + `phase4-prong-c-audit.md`.
- **Prong A.2 (BG-thread `runBlocking` polish)** — deferred but optional; not blocking release.
- **Prong D-grep (`ReadAction.compute` / `runReadAction` deprecation)** — not yet started; can run pre-release without a live IDE.

---

## Resumption protocol

Anyone (or any agent) picking this up should:

1. Read `docs/architecture/phase4-prong-c-plan.md` and `phase4-prong-a-plan.md` for the established Prong shape (audit doc → plan doc → site-by-site commits → reviewer).
2. Read `~/.agents/skills/intellij-plugin-performance/SKILL.md` §1 (Diagnosis) and §5 (Verification) — the skill is the source of truth for how to capture evidence per perf class.
3. Read `references/profiling-recipes.md` in the same skill — copy-pasteable capture commands.
4. Pick a parked prong (B, D-profile, or E) and follow the prong-specific instructions below.
5. **Always capture evidence first.** Don't write a fix until the flame graph / DevTools trace identifies the actual hotspot.

---

## Prong B — EDT hotspots (profile-driven)

**Goal:** eliminate frame stalls (>16 ms) in scrolling JBList/JBTable, dialog opens, and tool-window tab switches. Targets: cell renderers, font derivation, paint paths, allocation pressure during scroll, LAF repaint storms, gutter/annotator overhead.

### Capture targets

For each, capture an Async Profiler `wall` flame graph during a 5-second interaction. Plugin frames aggregating > 10 ms across the 5-second capture = hotspot.

| Surface | Repro |
|---|---|
| Sprint tab JBTable scroll | Open Workflow > Sprint, scroll the ticket list rapidly for 5s |
| PR tab JBList scroll | Open Workflow > PR, scroll the PR list rapidly for 5s |
| Build tab JBTable scroll | Open Workflow > Build, scroll job rows |
| Quality tab issue list scroll | Open Workflow > Quality, scroll the issue list |
| Automation queue scroll | Open Workflow > Automation, scroll the queue table |
| Handover tab interactions | Hover over QA clipboard rows, expand/collapse sections |
| Tool window first-open | Cold IDE start → click Workflow icon, time to first paint |
| Tab switch | From Sprint, click PR/Build/Quality/Automation/Handover; time to render |
| LAF switch | Toggle theme (dark ↔ light) with Workflow open |
| Sonar gutter icons | Open a file with Sonar issues, scroll up/down |
| Jira commit-prefix gutter | Open a file referenced by an active ticket |
| Create PR dialog open | Trigger Create PR — measure dialog open + initial render |
| Connections settings dialog open | Settings > Workflow Orchestrator > Connections |
| Hover state on rows | Hover slowly across 20+ rows in any list |

### Capture commands (Linux/macOS — Async Profiler)

```bash
# 1. Find the runIde PID
PID=$(jps -l | grep idea | awk '{print $1}')

# 2. Wall-clock profile (5s)
./profiler.sh -d 5 -e wall -f /tmp/wall.html "$PID"

# 3. Reproduce the slow interaction during those 5s
# 4. Open /tmp/wall.html — look for plugin frames > 10 ms aggregate
```

Windows: Async Profiler 4.3 has no Windows port. Use IntelliJ built-in CPU Profiler (Ultimate, sampled mode) or JFR via `jcmd`. See `~/.agents/skills/intellij-plugin-performance/references/profiling-recipes.md` §JFR.

### Expected hotspots (audit pre-empted, confirm via profile)

These are *suspects* from the upgraded skill, not certainties:

- **`Font.deriveFont(...)` in `paintComponent` or renderer** — see anti-pattern AP-21. On Windows GDI, 2-5 ms per call; cache in companion via `by lazy`.
- **`Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints")` per paint** — JNI call. Cache once. (See SKILL.md §4.9.)
- **`<html>...</html>` in JLabel cell renderer** — full HTML parser per paint. Use `SimpleColoredComponent` (AP-22).
- **Object allocation in `getListCellRendererComponent`** — single-instance mutation pattern (SKILL.md §4.8 + scroll-jank-playbook.md §3.9).
- **`fireTableDataChanged()` for partial update** — re-renders all rows; use `fireTableRowsUpdated(i, i)` (AP-23).
- **`revalidate()` in `paintComponent`** — layout storm (AP-25).

### Commit pattern

For each fix:
- One commit per file (or tight cluster of identical fixes within a file).
- Commit message format: `perf(<module>): <fix> — wall p50 X ms → Y ms during <interaction>` (per skill §5).
- Evidence: paste the relevant flame graph delta or attach a screenshot path in the commit body.

### Exit criteria

- Each captured surface shows zero plugin frames > 10 ms aggregate over 5s.
- Async Profiler flame graphs (or DevTools traces for JCEF surfaces — see Prong E) for each surface stored at `docs/architecture/phase4-baseline/<surface>-after.html`.
- `verifyPlugin buildPlugin` green.
- All module tests still pass.

### Estimated commit count

Unknown until profiling runs. Likely 5-15 commits depending on findings. Not all suspects will surface as hot in any given codebase — measure first.

---

## Prong D-profile — PSI batching for hot paths

**Goal:** identify and fix `runReadAction` / `ReadAction.compute` sites that show as freeze contributors in profiling, beyond the grep-driven sweep already done in Prong D-grep.

**Note:** Prong D-grep is the cheap, deterministic part — 2026.1 deprecated `ReadAction.compute` / `ReadAction.run` / `runReadAction`. A grep-and-replace pass following SKILL.md §4.4b can land before live-IDE capacity is available. **D-grep is not parked**; it can run anytime. Only the *profile-driven* portion (deciding which of the resulting `readAction { }` calls are hot enough to warrant `ReadAction.nonBlocking` or batching) is parked here.

### Capture target

Async Profiler `cpu` and `wall` during:
- File open of a large project (slow file scan)
- Find Usages on a frequently-referenced symbol
- Sonar gutter rendering on a large file
- Jira ticket-key extraction during branch switch (BranchChangeTicketDetector)

### Decision tree per hot site

Per SKILL.md §4.2:

| Symptom | Fix |
|---|---|
| Long PSI work blocking writes | `ReadAction.nonBlocking { }.inSmartMode(project).expireWith(disposable).submit(AppExecutorUtil.getAppExecutorService())` |
| Repeated PSI walks for same data | Cache via `CachedValuesManager.getCachedValue` |
| Per-element work in `getLineMarkerInfo` | Pre-compute on a background service and look up by file:line key |
| ExternalAnnotator doing PSI in `apply()` | Move to `doAnnotate()` (background phase) — see SKILL.md §4.15 |

### Estimated commit count

5-10, depending on findings.

---

## Prong E — JCEF rendering (profile-driven)

**Goal:** ensure the agent chat surface stays responsive at 60 FPS during long conversations (100-500+ messages) and streaming-token rendering. Hotspots usually surface as bridge round-trip costs, DOM update batching, and unvirtualized lists.

### Capture targets

| Surface | Repro |
|---|---|
| Long conversation render | Resume a saved session with 100+ messages — measure first paint |
| Long conversation scroll | Scroll the message list with 100+ messages |
| Streaming token render | Trigger an LLM response of 1000+ tokens, observe smoothness |
| History tab open | Open the History tab with 50+ sessions — first paint to interactive |
| Bridge round-trip | Click on a session in History, time `_showSession` round-trip |
| File mention autocomplete | Type `@` and a partial file name, observe popup latency |
| Toast notification | Trigger a toast (e.g. tool result) and observe paint cost |

### Capture commands (Chrome DevTools)

```
Help > Find Action > Show Developer Tools for JCEF
```

Or programmatically (in dev `runIde`):

```kotlin
agentBrowser.cefBrowser.openDevTools(null, true)
```

Then:
1. Open Performance tab
2. Click Record
3. Reproduce the slow interaction
4. Click Stop
5. Look for: Long Tasks (> 50 ms) at the top, FPS drops, big commit phases

### Expected hotspots

From `~/.agents/skills/intellij-plugin-performance/references/jcef-perf-playbook.md`:

- **No virtualization for the message list** — 500 messages = 500 DOM subtrees. Use `react-virtuoso` (recommended) for variable-height messages with `followOutput`.
- **`setState` per streaming token** — should batch via `requestAnimationFrame` (jcef-perf-playbook §3.2).
- **Markdown re-parse per token** — only re-parse the tail message, not the whole conversation.
- **Bridge round-trip per token** — `JBCefJSQuery` pool exhaustion. Tune `-DJS_QUERY_POOL_SIZE=16` if needed.
- **Synchronous response from a bridge handler** — handler runs on EDT; never `runBlocking` inside. Use the async-response pattern from jcef-perf-playbook §3.7.
- **CSP forcing style recalc** — disallowing inline styles forces extra computation per repaint.

### AgentController context

- 5 `runBlocking` sites in JCEF bridge callbacks were already fixed in Prong A — those are correctness fixes, not perf. Prong E is about rendering throughput inside JCEF.
- Out-of-process JCEF (default since IntelliJ 2025.1) — no behavior change vs the in-process variant for our patterns.
- `StreamBatcher` (`agent/ui/StreamBatcher.kt`) already coalesces SSE chunks at 16 ms on the Kotlin side — verify it's still effective (5000 → ~300 bridge calls per response per the file's comment). If not, that's the first fix.

### Estimated commit count

3-8, depending on whether virtualization needs to change (1 commit) plus per-hotspot fixes.

---

## Sequencing when resumed

1. **Run Prong D-grep first** — does not need live IDE. See `~/.agents/skills/intellij-plugin-performance/SKILL.md` §10 — `ReadAction.compute / run / runReadAction` deprecated in 2026.1. Grep-and-replace pass following §4.2 decision tree. Plan doc in the same shape as Prong A and C (audit → plan → site-by-site).
2. **Capture baseline** with the live IDE for all 3 parked prongs simultaneously. Save flame graphs / DevTools traces under `docs/architecture/phase4-baseline/`.
3. **Address surfaces in order of measured cost.** A B-prong hotspot at 80 ms wall-time is more important than an E-prong improvement of 30 ms.
4. **One commit per hotspot per surface.** Co-located fixes (e.g. all cell-renderer hotspots in `:bamboo`) can bundle if the change shape is identical and the evidence is per-site.
5. **Each commit must cite measured before/after** in its message. SKILL.md §0 — evidence or it didn't happen.

## Release gate

After Prong A.2 (optional) + Prong D-grep (cheap), Phase 4 reaches a *correctness-complete* milestone: every grep-detectable anti-pattern is fixed, and the only deferred work is profile-driven optimization. **The plugin is releasable at this point** (per branch memory's release-gate-after-Phase-4 plan).

If you want to ship before live-IDE profiling lands, the release at this milestone is sound:
- Bump `pluginVersion` in `gradle.properties`
- `./gradlew clean buildPlugin`
- `git push origin refactor/cleanup-perf-caching` (or rebase to main first)
- `gh release create vX.Y.Z` with the `build/distributions/*.zip`

Then resume profile-driven prongs in a follow-up release cycle.

---

## Document maintenance

When Prongs B / D-profile / E start landing commits, update this doc to reflect what's done vs what's still parked. When Phase 4 fully closes, this doc can move to `docs/architecture/archive/` rather than be deleted — it's the historical reasoning for why prongs were parked and how they were resumed.

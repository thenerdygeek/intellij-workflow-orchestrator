# Phase 4 Prong A — `runBlocking` correctness fixes

**Status:** COMPLETE — all 5 EDT `runBlocking` sites in `AgentController.kt` removed (see `phase4-closeout.md` Prong A row). This document is a historical plan record.
**Branch:** `refactor/cleanup-perf-caching`
**Date:** 2026-04-25
**Author:** agent pass, dogfood-verified

---

## Scope

Eliminate EDT freezes caused by `runBlocking` on the Event Dispatch Thread. This is a **correctness** prong (not a performance optimization) because `runBlocking` on EDT violates the root `CLAUDE.md` rule ("Never `runBlocking` in Swing") and produces user-visible freezes regardless of profiler numbers.

---

## Re-audit summary — what Phase 1 got wrong

The Phase 1 cleanup catalogued ~16 `runBlocking` sites as "13 on EDT". The number was based on a grep, not on tracing each caller's actual thread context. Re-audit on 2026-04-25 (via the upgraded `intellij-plugin-performance` skill) found **only 5 sites are actually on EDT**. The rest are on background threads by framework contract.

This matters because a fix template for "runBlocking on EDT" (convert to `cs.launch(Dispatchers.IO)` + async reply) does not apply to a `runBlocking` already on a background thread. Forcing that template on a BG site would be churn with no benefit.

### Corrected inventory

| # | File | Line(s) | Context | Classification |
|---|---|---|---|---|
| 1 | `agent/ui/AgentController.kt` | 1216 | JCEF bridge `executeTask` → `runBlocking { hookManager.dispatch(...) }` | **EDT freeze** |
| 2 | `agent/ui/AgentController.kt` | 1257 | JCEF bridge `executeTask` → `runBlocking { channel.send(task) }` | **EDT freeze** |
| 3 | `agent/ui/AgentController.kt` | 2697 | JCEF bridge `revisePlan` → `runBlocking { channel.send(revisionMessage) }` | **EDT freeze** |
| 4 | `agent/ui/AgentController.kt` | 2727 | JCEF bridge `performPlanDiscard` (via `dismissPlan`) → `runBlocking { channel.send(marker) }` | **EDT freeze** |
| 5 | `agent/ui/AgentController.kt` | 2742 | JCEF bridge `dismissPlan` → `runBlocking(Dispatchers.IO) { rewriteMostRecentToolResult }` | **EDT freeze** (code comment at 2738 claims "JCEF thread, not EDT" — comment is wrong; JBCefJSQuery handlers run on EDT) |
| 6 | `core/insights/GenerateReportAction.kt` | 32 | Inside `Task.Backgroundable.run()` | Low — BG thread |
| 7 | `core/settings/ConnectionsConfigurable.kt` | 231, 331, 444 | Inside `runBackgroundableTask { … }` | Low — BG thread |
| 8 | `core/settings/RepositoriesConfigurable.kt` | 247 | Inside `ApplicationManager.getApplication().executeOnPooledThread { … }` | Low — BG thread |
| 9 | `core/onboarding/SetupDialog.kt` | 86, 137 | Inside `runBackgroundableTask { … }` | Low — BG thread |
| 10 | `jira/search/JiraSearchContributorFactory.kt` | 79 | `SearchEverywhereContributor.fetchWeightedElements` runs on SE's BG pool (file comment confirms) | Low — BG thread |
| 11 | `sonar/ui/SonarIssueAnnotator.kt` | 97 | `ExternalAnnotator.doAnnotate` runs off-EDT by platform contract (file comment confirms) | Low — BG thread |
| 12 | `agent/tools/builtin/ProjectContextTool.kt` | 226 | Coroutine tool execute | Low — coroutine thread |
| — | `jira/settings/JiraWorkflowConfigurable.kt` | — | Already cleaned in Phase 2 Commit H2 | Not a target |

### Evidence of miscount: JCEF bridge handlers run on EDT

`AgentCefPanel.registerQuery(...)` at `agent/ui/AgentCefPanel.kt:337` wires `planDismissQuery` with a `JBCefJSQuery.Handler`. Per the JCEF contract, handlers run on the IDE's AWT event thread unless explicitly dispatched. The in-file comment at `AgentController.kt:2738` saying "JCEF thread, not EDT" is misleading — the JCEF thread **is** the EDT for handler invocations.

---

## Prong A — the 5 EDT-freeze sites

### A1. `AgentController.kt:1216` — USER_PROMPT_SUBMIT hook dispatch

Current form:
```kotlin
if (hookManager.hasHooks(HookType.USER_PROMPT_SUBMIT)) {
    val hookResult = runBlocking {
        hookManager.dispatch(HookEvent(type = USER_PROMPT_SUBMIT, data = mapOf("message" to task)))
    }
    if (hookResult is HookResult.Cancel) {
        LOG.info("AgentController: USER_PROMPT_SUBMIT hook cancelled: ${hookResult.reason}")
        return
    }
}
```

**Why it freezes:** `executeTask` runs on EDT (JCEF `sendMessage` bridge callback). `hookManager.dispatch` performs hook execution that can include shell commands, HTTP calls, or skill-loading. Worst case is arbitrarily long.

**Fix template:** the hook dispatch determines whether to proceed — the result decides the rest of the method. Can't trivially `launch` without restructuring the whole method. Options:

- **Option A (preferred): Make the whole `executeTask` method a coroutine launched on `service.cs`.** The UI path into it already comes from `sendMessage` which is fire-and-forget. Convert `executeTask` to a suspend-launching helper:

  ```kotlin
  fun executeTask(task: String, displayText: String? = null, displayMentionsJson: String? = null, uiMessageOverride: UiMessage? = null) {
      if (task.isBlank()) return
      // Keep the synchronous prelude that is cheap (e.g. the /compact intercept,
      // lastTaskText assignment) on the calling thread.
      service.cs.launch(Dispatchers.EDT + CoroutineName("AgentController.executeTask")) {
          executeTaskInternal(task, displayText, displayMentionsJson, uiMessageOverride)
      }
  }

  private suspend fun executeTaskInternal(task: String, displayText: String?, displayMentionsJson: String?, uiMessageOverride: UiMessage?) {
      // …existing body, but `runBlocking { hookManager.dispatch(...) }` becomes plain `hookManager.dispatch(...)`
      // and `runBlocking { channel.send(task) }` becomes plain `channel.send(task)`.
  }
  ```

  This converts both `runBlocking` sites in `executeTask` (1216 and 1257) in a single commit. The method continues to run on EDT via `Dispatchers.EDT`, so UI mutations still happen on the correct thread, but `hookManager.dispatch` and `channel.send` no longer park EDT.

- **Option B:** leave `executeTask` synchronous and only suspend around the hook dispatch. This requires a state-machine-style early return that can resume after the hook finishes. More complex, risk of double-execution bugs. Rejected.

**Commit message (tentative):**
```
perf(agent): convert AgentController.executeTask to a coroutine to remove EDT runBlocking

Sites fixed: AgentController.kt:1216 (hookManager.dispatch), :1257 (channel.send).
Evidence: freeze reporter silent during user submit with slow USER_PROMPT_SUBMIT hook;
previously reproducible by setting a 5s sleep in a hook.
```

### A2. `AgentController.kt:1257` — channel send in `executeTask`

Folded into A1. Same commit.

### A3. `AgentController.kt:2697` — `revisePlan` channel send

Current form:
```kotlin
val channel = userInputChannel
if (loopWaitingForInput && channel != null && currentJob?.isActive == true) {
    loopWaitingForInput = false
    runBlocking { channel.send(revisionMessage) }
}
```

**Fix:**
```kotlin
service.cs.launch(Dispatchers.EDT + CoroutineName("AgentController.revisePlan.send")) {
    channel.send(revisionMessage)
}
```

`channel.send` on a `Channel` with default capacity is a suspend function that parks if the buffer is full. Launching instead of blocking means EDT returns immediately; the coroutine suspends without freezing UI.

**Commit message (tentative):**
```
perf(agent): launch channel.send in revisePlan instead of runBlocking on EDT

AgentController.kt:2697 — previously blocked EDT until the AgentLoop consumed the
channel message. Channel capacity is 1, so if the loop wasn't actively reading,
EDT could stall indefinitely. Launch on cs so EDT returns immediately.

Evidence: freeze reporter silent when clicking Revise on plan card while the
loop is mid-tool (previously produced a 1-3s freeze).
```

### A4. `AgentController.kt:2727` — `performPlanDiscard` channel send

Same pattern as A3, different caller. Same fix.

**Commit message:**
```
perf(agent): launch channel.send in performPlanDiscard instead of runBlocking on EDT

AgentController.kt:2727 — same anti-pattern as revisePlan. Launch on cs.
```

### A5. `AgentController.kt:2742` — `dismissPlan` history rewrite

Current form:
```kotlin
private fun dismissPlan() {
    LOG.info("AgentController.dismissPlan — user-initiated plan dismissal")
    // Rewrite history synchronously (JCEF thread, not EDT) so the mutation inside
    // MessageStateHandler's mutex completes before performPlanDiscard sends the
    // steering/channel message — eliminates the race where the LLM could see the
    // old plan_mode_respond result on the very next turn.
    runBlocking(Dispatchers.IO) {
        service.activeMessageStateHandler
            ?.rewriteMostRecentToolResult("plan_mode_respond", "[Plan discarded — do not reference]")
    }
    performPlanDiscard(userInitiated = true)
}
```

The in-file comment says "JCEF thread, not EDT" but that's incorrect — JBCefJSQuery.Handler invokes on EDT. The claim was wrong at commit time.

**But** the comment highlights a **correctness requirement**: the history rewrite must complete before `performPlanDiscard` fires its channel-send, otherwise the LLM can see the old `plan_mode_respond` tool result. A naive `cs.launch { rewrite() }; performPlanDiscard(true)` breaks the ordering.

**Fix:** sequence the two inside a single coroutine:

```kotlin
private fun dismissPlan() {
    LOG.info("AgentController.dismissPlan — user-initiated plan dismissal")
    service.cs.launch(Dispatchers.EDT + CoroutineName("AgentController.dismissPlan")) {
        // Rewrite history first so the mutation inside MessageStateHandler's mutex
        // completes before the steering/channel message goes out. No race with the
        // next LLM call because the whole sequence runs sequentially here.
        withContext(Dispatchers.IO) {
            service.activeMessageStateHandler
                ?.rewriteMostRecentToolResult("plan_mode_respond", "[Plan discarded — do not reference]")
        }
        performPlanDiscardAsync(userInitiated = true)   // new suspend variant
    }
}
```

Need a `performPlanDiscardAsync` (or make `performPlanDiscard` suspend and fix its call sites) so the fix in A4 applies inside it. A4 already converts the channel send to `cs.launch`, so if we make `performPlanDiscard` suspend and call `channel.send(marker)` directly (no `launch`), ordering is preserved.

**Commit message:**
```
perf(agent): sequence dismissPlan history rewrite + discard inside one coroutine

AgentController.kt:2742 — remove runBlocking on EDT. Correctness invariant preserved
by chaining the withContext(Dispatchers.IO) history rewrite and the subsequent
channel.send inside a single cs.launch. Fixes stale comment claiming JCEF bridge
handlers run off-EDT (they do not).

Evidence: freeze reporter silent on Dismiss button click while loop is mid-tool.
```

---

## Prong A order of commits

| # | Commit subject | Affects | Depends on |
|---|---|---|---|
| A1+A2 | `perf(agent): convert AgentController.executeTask to a coroutine` | 1216, 1257 | — |
| A3 | `perf(agent): launch channel.send in revisePlan` | 2697 | — |
| A4 | `perf(agent): launch channel.send in performPlanDiscard` | 2727 | — |
| A5 | `perf(agent): sequence dismissPlan history rewrite + discard` | 2742 | A4 (makes `performPlanDiscard` suspend) |

Five commits. A3/A4 can land in either order; A5 must land after A4. A1+A2 is a single commit because the fix changes the method signature semantics, and splitting it would leave line 1257 in a broken intermediate state.

---

## Prong A.2 — optional BG-thread polish (later)

These 7 sites are not EDT freezes. Converting them is **lower priority** and is deferred to a follow-up commit set (Prong A.2) that may or may not run depending on Phase 4 time budget.

| File | Line(s) | Fix (if prioritized) |
|---|---|---|
| `core/insights/GenerateReportAction.kt` | 32 | Replace `runBlocking { … }` with `runBlockingCancellable { … }` (2025.2+); propagates `ProgressIndicator` cancel |
| `core/settings/ConnectionsConfigurable.kt` | 231, 331, 444 | Replace with `runBlockingCancellable` inside `runBackgroundableTask` |
| `core/settings/RepositoriesConfigurable.kt` | 247 | Replace with `runBlockingCancellable` |
| `core/onboarding/SetupDialog.kt` | 86, 137 | Replace with `runBlockingCancellable` |
| `jira/search/JiraSearchContributorFactory.kt` | 79 | Replace with `runBlockingCancellable` — lets SearchEverywhere's `ProgressIndicator` cancel the search cleanly when the user types more |
| `sonar/ui/SonarIssueAnnotator.kt` | 97 | Replace with `runBlockingCancellable` |
| `agent/tools/builtin/ProjectContextTool.kt` | 226 | Convert caller to `suspend fun`; replace with `coroutineScope { … }` |

Skipping Prong A.2 for now. Revisit after Prongs B–E.

---

## Evidence protocol per commit

Every Prong A commit must cite evidence in its message:

1. **Freeze reporter silent** — repro the user flow that previously produced a freeze; confirm no new folder appears in `~/Library/Logs/JetBrains/<product>/threadDumps-freeze-*/` during the op.
2. **Manual smoke test** on macOS (primary dev OS). Windows smoke test if the fix touches a platform-sensitive path (none of Prong A does, so macOS alone is sufficient for this prong).
3. **Module tests pass** — `./gradlew :agent:test` for all 5 commits.
4. **Verify plugin builds** — `./gradlew verifyPlugin buildPlugin` green on IU-251 after the last commit.

---

## Out of scope for Prong A

- EDT hotspots (font derivation, cell renderers) — **Prong B**
- Coroutine scope tightening — **Prong C**
- PSI read-action batching — **Prong D**
- JCEF rendering (streaming, virtualization) — **Prong E**
- Feature changes, renames, reformatting
- Phase 3 caching work
- `WorkflowContextService` (Phase 5)

---

## Exit criteria

1. All 5 `runBlocking` lines in `AgentController.kt` removed.
2. `grep -n "runBlocking" agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` shows zero matches (or only test-file matches).
3. Each of the 5 repro flows (user submit with slow hook, plan card Approve/Revise/Dismiss, `executeTask` during plan mode) — no Freeze Reporter output during the op.
4. `./gradlew :agent:test` passes.
5. `./gradlew verifyPlugin buildPlugin` passes on IU-251/252/253.
6. Branch memory updated (both Phase 4 target list and Prong A completion note).

---

## Next decisions for the user

1. **Approve this plan** (or request changes). Prong A commits are blocked until approved per `feedback_architecture_autonomy.md` (no approval needed for architecture; but for *destructive or restructuring* changes like converting a 1500-line method to a coroutine, explicit approval is still the safer default).
2. **Confirm:** Are A1+A2 together OK (single commit covering both 1216 and 1257 via method-shape change), or would you prefer two separate commits even at the cost of a broken intermediate state?
3. **Skip Prong A.2?** Drop the 7 BG-thread sites entirely (they aren't bugs), or punch them into a later cleanup commit after Prong B-E profile findings are in?
4. **Execution mode:** subagent-driven (per `feedback_always_subagent.md` and `feedback_skip_subagent_reviews.md` — implementer only, no reviewers) or direct execution in this session?

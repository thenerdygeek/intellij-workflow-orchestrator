# Context Handoff — `design/unified-message-queue` branch

**Date:** 2026-06-20
**Branch:** `design/unified-message-queue` @ `f95591bfe` — **29 commits ahead of `main`** (merge-base `6cd1440ee`)
**Status:** Two features + two bug fixes. **All reviewed, all automated tests green. NOT merged.** In-IDE smoke + finishing decision pending.

---

## TL;DR

This branch carries **two stacked features** (the second builds on the first) plus **two bug fixes**:

1. **Unified Message Queue** — one typed, source-aware queue replacing the 4 ad-hoc async-message-injection paths into the agent ReAct loop (user steering / monitor / delegation / background-terminal). Fixes the bug where every async event was framed to the LLM as "the user sent a message."
2. **Async Event Cards** — visible, persisted, expandable chat-timeline cards when a background process completes or a monitor fires (built on the queue's durability for the idle path).
3. **Bug fix — top-bar chips** (`866f00db3`): `BackgroundIndicator`/`MonitorIndicator` never populated (JSON-string-vs-array bridge mismatch).
4. **Bug fix — monitor double-card** (`632e03aca`): part of feature 2's final review.

Each feature went: brainstorm → spec → spec review (subagent panel) → plan → plan review (subagent) → subagent-driven development with a per-phase review → final whole-branch review → comprehensive tests.

**Decision pending:** how to finish (push+PR / merge to main / keep). The user wants **both features on this one branch**.

---

## Commit map

**Feature 1 — Unified Message Queue (16 commits):** `ca27803c0` (spec) → `201b2b179`,`1199e2524`,`bc9ec3984`,`0698b5ec8` (P0 pure core) → `a68563f8b` (P1 live swap) → `41b75856a`,`b0b9a8f19`,`3b3e99935`,`3625ccc3a`,`cf6c4a12b` (P2 migrate producers + collapse stores) → `ca6888dec` (stale comments) → `d6463a811`,`6b4e0b823` (P3 remove shim + docs) → `bc063a5b6` (polish) → `7a7a04e39` (hardening, +23 tests).

**Bug fix:** `866f00db3` (top-bar chips).

**Feature 2 — Async Event Cards (11 commits):** `6453cc1c8` (spec) → `f3e6862f1`,`bad3ff60b` (P0 model+presenter) → `59ddfead0`,`9d927df21` (P1 webview) → `cfa28ea63`,`7e75b1d3f` (P2 seam+bg) → `29d06edfa` (P3 monitor) → `fd3b3b116` (P4 resume synthesis) → `94fa65f99` (P5 docs) → `632e03aca` (final-review fix) → `f95591bfe` (hardening, +27 tests).

---

## Feature 1 — Unified Message Queue

**Spec:** `docs/superpowers/specs/2026-06-19-unified-message-queue-design.md` · **Plan:** `docs/superpowers/plans/2026-06-19-unified-message-queue.md` (gitignored)

**What it does.** New package `agent/src/main/kotlin/.../loop/queue/`: `QueuedMessage` envelope + `QueueSourceKind {USER,DELEGATION,BACKGROUND,MONITOR}` + `QueueSourcePolicy` (priority/durable/autoWakesIdle/resetsUserSilenceCounter/defersCompletion/coalesceKey/frame) + `QueueSourceRegistry` + `UnifiedMessageQueue` (JVM-lock guarded; timestamp-asc drain with priority tiebreak; coalescing; durable persist) + `QueuePersistence` (one `sessions/{id}/pending_queue.json`).

**Key wiring.** `AgentService.queueForSession(sessionId)` (per-session `ConcurrentHashMap`, the single instance the loop drains AND `enqueueToSession` enqueues into). `AgentLoop` Stage 0.5 drains it (one combined `addUserMessage` per drain, per-source framed, only USER resets `iterationsSinceLastUser`). Producers: background (`BackgroundCompletionCoordinator`) + delegation (`enqueueNudgeForSession`) via `enqueueToSession`; monitor via a plain `enqueueToQueue` so `MonitorManager.wakeIdle` still returns `WakeOutcome` (budget intact). `resumeSession` drains the queue into the `[TASK RESUMPTION]` preamble.

**Migration notes (IMPORTANT for next release).** `BackgroundPersistence` + `DelegationNudgePersistence` are **retained one release** as legacy resume readers (their writers were removed); `MonitorPersistence` keeps its `MonitorSpec` half (resume re-arm) — only its notification *writer* was removed. **Delete the legacy readers + `BackgroundPersistence`/`DelegationNudgePersistence` next release** (search `// LEGACY: retire next release` in `AgentService.resumeSession`).

**Bugs it fixed (verified in review):** mislabeling (async events framed as user steering); `iterationsSinceLastUser` reset by non-user events; a latent background double-delivery on auto-wake.

---

## Feature 2 — Async Event Cards

**Spec:** `docs/superpowers/specs/2026-06-20-async-event-cards-design.md` · **Plan:** `docs/superpowers/plans/2026-06-20-async-event-cards.md` (gitignored)

**What it does.** New UI-only `UiSay.ASYNC_EVENT` `UiMessage` carrying `AsyncEventCardData(id, kind, sourceId, label, status, summary, details, timestamp, spillPath)`. Mirrors `DELEGATION_CARD` end-to-end. Persisted to `ui_messages.json`, **never** `api_conversation_history.json` (invisible to the LLM).

**Key files:**
- Kotlin: `session/UiMessage.kt` (model), `ui/AsyncEventCardPresenter.kt` (pure event→card; status: bg EXITED+0→SUCCESS else FAILURE; monitor ALERT→ALERT else NOTABLE; id `bg-{bgId}-{occurredAt}` / `mon-{monitorId}-{ts}`), `ui/AsyncEventResumeSynthesis.kt` (pure resume rebuild + dedup), `AgentService.appendAsyncEventCardToSession`/`setAsyncEventCardListener`/`emitAsyncEventCard` (+ resume synthesis in `resumeSession`), `ui/AgentController.pushAsyncEventCard` (persist + live-push gated on `viewedSessionId`), `AgentCefPanel`/`AgentDashboardPanel.pushAsyncEventCard`.
- Producers: background = `AgentController.subscribeToBackgroundCompletions` (replaced the old `appendStatus` bubble with the card) + `BackgroundCompletionCoordinator` stashes `meta["card"]`; monitor = widened `MonitorManager.deliverToLoop/wakeIdle (monitorId, severity, text)` → `emitCard` seam + `AgentMonitorCoordinator` stashes `meta["card"]`.
- Webview: `components/agent/AsyncEventCard.tsx`, `ChatView.renderItem` branch, `chatStore.addAsyncEventCard`, `bridge/jcef-bridge.ts _pushAsyncEventCard` (JSON string → `JSON.parse` → store), `bridge/types.ts`/`globals.d.ts`.

**Design decisions:** real-time card for the **focused** session (push + persist); **idle/non-focused** events ride the queue's `meta["card"]` durability and are **synthesized on resume** (dedup by `AsyncEventCardData.id` against the loaded `savedUiMessages`, appended onto the resume-local handler inside `cs.launch`). Scope = background + monitor only (delegation already has `DELEGATION_CARD`).

**The B1 trap (do not regress):** at resume-drain time the session is NOT active (`activeMessageStateHandler` is set later in `executeTask`), so resume synthesis must append onto the resume-local `handler`, NOT via `appendAsyncEventCardToSession`. Dedup against in-memory `savedUiMessages`; call `drainGrouped()` exactly once.

---

## Bug fix — top-bar indicator chips (`866f00db3`)

`BackgroundIndicator`/`MonitorIndicator` never showed because `AgentCefPanel` sent the snapshot as a JSON **string** (`JsEscape.toJsString`) but the webview handlers (`__receiveBackgroundUpdate`/`__receiveMonitorUpdate` + hydration) treated it as an array (`Array.isArray(string)===false` → `[]`). Fixed with a `coerceSnapshotArray` helper (JSON.parse, tolerant) at all 4 bridge boundary sites in `jcef-bridge.ts`. TDD; full webview suite green.

---

## Tests (all green at `f95591bfe`)

- `./gradlew :agent:test` — Kotlin unit + source-text contract tests (AgentService/AgentLoop aren't unit-instantiable → contracts).
- `cd agent/webview && npx vitest run` — **768/768** (120 files).
- `./gradlew verifyPlugin` — IntelliJ plugin verifier (the internal-API warnings are pre-existing, in `RuntimeExecSharedKt`/`ServiceGraphActionKt`).
- `./gradlew buildPlugin` — packages `build/distributions/intellij-workflow-orchestrator-0.87.2.zip`.
- Hardening suites: `UnifiedMessageQueueBehaviorTest` (+23), `AsyncEventCardHardeningTest` (+27).
- Both features had full **whole-branch opus reviews**; each drove a real fix (queue: `MonitorPersistence` half-keep + drain-legacy-not-fold; cards: monitor ts-unification).

---

## ⚠ Pending: in-IDE smoke (the only untestable surface — needs `./gradlew runIde`)

**Queue (feature 1):** background completion shows correctly framed (`[BACKGROUND COMPLETION]`, not "user sent a message"), live and idle (auto-wake + resume), **once**; user steering unchanged; delegation reply + monitor live/idle delivery; monitor wake-budget/flood guard still trips.

**Cards (feature 2):** background/monitor cards appear in chat (expandable, output tail); status-dot colors (success=green, failure=amber, alert=red); the top-bar chips now populate during active work.

**Known UX nuance (final-review Minor, not data-loss):** a card synthesized for an **idle** session is persisted but currently renders on the **next reopen**, not the in-progress resume (the webview loads session state just before synthesis appends). If "appear on the same resume" is wanted, it's a small follow-up (push the synthesized cards live during resume).

---

## Deferred follow-ups (Minor, non-blocking — full list in the SDD ledger)

- **Queue:** `QueuePersistence.save` no tmp-cleanup on failure (mirrors `BackgroundPersistence`); AgentService FQNs vs imports (avoids detekt `ImportOrdering` baseline); a couple monitor test lambdas hand-roll values.
- **Cards:** `fromMonitor` `else` vs exhaustive `when` over `Severity`; generic "N events" multi-line monitor summary; `spillPath` non-navigable plain text; `label` row always rendered; `AsyncEventCardWiringContractTest` T2 slice over-captures; resume-card-on-next-reopen (above).
- **Next release:** delete the unified-queue legacy resume readers (`// LEGACY: retire next release`).

---

## How to resume

1. **Ledger (recovery map):** `.superpowers/sdd/progress.md` (gitignored) — per-task completion + every Minor finding. Trust it + `git log` after compaction.
2. **Specs (tracked):** the two `docs/superpowers/specs/2026-06-{19,20}-*-design.md`. **Plans (gitignored):** `docs/superpowers/plans/2026-06-{19,20}-*.md` — full task-by-task with code.
3. **Finishing:** the branch is reviewed + green; pick push+PR / merge / keep. The user wants both features on this one branch.

## Session traps/lessons (this project)

- **Source-text sentinel-slice:** `DelegationConversationNarrationTest` slices `AgentService.kt` between `fun delegatedIncomingTaskText` and `fun mapLoopResultToDelegationResult` — keep new functions outside that range; run FULL `:agent:test`.
- **Build-cache trap:** lambda arity/suspend signature changes need `:agent:clean :agent:test --rerun --no-build-cache`.
- **JCEF bridge string-vs-array:** Kotlin→JS structured payloads go as JSON strings; the JS handler MUST `JSON.parse` (the top-bar bug). New pushes use Convention A (single object string + parse), not `coerceSnapshotArray`.
- **macOS TCC:** the project lives under `~/Desktop` (TCC-protected). If the terminal loses Full Disk Access, file reads return `EPERM`/"operation not permitted" — grant it in System Settings or move the project. (Bash sandbox: read-only ops were run with the sandbox disabled.)
- **`docs/superpowers/plans/` is gitignored** (`.gitignore:61`); specs are tracked. Plans + SDD scratch live on disk only.

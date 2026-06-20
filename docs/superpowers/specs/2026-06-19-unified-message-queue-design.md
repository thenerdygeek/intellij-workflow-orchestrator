# Unified Message Queue — Design Spec

**Date:** 2026-06-19
**Module:** `:agent`
**Status:** Reviewed (3-reviewer panel, code-verified) → revised. Pending user sign-off.
**Author:** agent design session

> **Review pass (2026-06-19).** Three adversarial reviewers (architecture/concurrency,
> migration/risk, semantics/testing) verified the spec against live code. Verdict:
> *sound-with-fixes*. Folded in: don't delete `MonitorPersistence` wholesale (keep `MonitorSpec`
> half); replace the non-idempotent one-time fold with drain-legacy-on-resume-for-one-release; add
> a single-item `remove(id)` API for the pill-cancel path; one combined `addUserMessage` + one
> `withEnvDetails` (per-group wrapping self-strips); live `frame()` carries an action directive;
> timestamp-ordered drains with priority as tiebreaker (fixes referent inversion); pin a JVM lock
> (not coroutine Mutex); account for the 2 extra plan-steering producers; budget the
> `SessionUiCallbacksParityTest` / `AgentLoopExitDrainTest` / sentinel-slice test blast radius.
> Confirmed *in the spec's favour*: the double-framing bug, the counter-reset bug, and a latent
> background double-delivery bug the blank-wake design fixes.

---

## 1. Problem & Motivation

Four independent producers push *asynchronous* messages into a running (or idle) ReAct
agent loop:

| Producer | Live entry point | Idle entry point |
|---|---|---|
| **User steering** (typed while busy) | `AgentController` → `steeringQueue.offer(SteeringMessage)` (`AgentController.kt:2449`) | n/a (user can only type into a focused session) |
| **Monitor** events | `AgentMonitorCoordinator.deliverToLoop` → `enqueueSteeringMessage` | `MonitorPersistence.appendPendingNotification` + `autoWakeIdleSession` |
| **Delegation** result/question | `enqueueNudgeForSession` → `enqueueSteeringMessage` (`AgentService.kt:490`) | `persistDelegationNudgeForLaterResume` + `autoWakeIdleSession` |
| **Background terminal** completion | `BackgroundCompletionCoordinator` → `enqueueSteeringMessage` | `BackgroundPersistence.appendCompletion` + `autoWakeIdleSession` |

> **Queue ownership (corrected per review).** The `ConcurrentLinkedQueue<SteeringMessage>` is
> **owned by `AgentController` (`AgentController.kt:322`)**, not `AgentLoop`. The user producer
> offers into it directly (`AgentController.kt:2449`); it is passed *into* the loop as a
> constructor param via `SessionUiCallbacks.steeringQueue` (`AgentController.kt:2545`). Only the
> **three async producers** route through `AgentLoop.enqueueSteeringMessage` (`AgentService.kt:493`
> delegation, `BackgroundCompletionCoordinator.kt:69` background, `AgentMonitorCoordinator.kt:139`
> monitor). The migration must re-home a *controller-owned, cross-thread-shared* field.
>
> **Two additional live producers also ride this queue (corrected per review):**
> `AgentController.revisePlan` (`steer-revise-…`, `AgentController.kt:4927`) and
> `performPlanDiscard` (a `[User dismissed the pending plan…]` marker, `AgentController.kt:4959`).
> The dismiss marker is a **system instruction, not user feedback** — under the unified design it
> should NOT inherit the user prefix. The 4-producer inventory is really **6 enqueue sites**.

The **live path is already accidentally "unified"** — everything funnels through one
`ConcurrentLinkedQueue<SteeringMessage>` (user offers directly; the three async producers go via
`AgentLoop.enqueueSteeringMessage(text)`, `AgentLoop.kt:1828`) → drained at Stage 0.5
(`AgentLoop.kt:947-960`). But the unification is **lossy and semantically wrong**:

1. **`SteeringMessage(id, text, timestamp)` (`AgentLoop.kt:101`) carries no source identity.**
   Once enqueued, a monitor alert is indistinguishable from a user message.
2. **Every live-drained item is wrapped in `STEERING_MESSAGE_PREFIX`** (`AgentLoop.kt:730`):
   *"The user sent an additional message while you were working. Incorporate their feedback…"*.
   So a background-process exit or a monitor event is presented to the LLM **as if the user
   typed it**. For background completions this is *doubly* wrong: the coordinator already
   frames the text via `buildCompletionSystemMessage`, and then the drain prepends the user
   prefix on top.
3. **The idle path is NOT unified.** Three separate persistence classes
   (`MonitorPersistence`, `DelegationNudgePersistence`, `BackgroundPersistence`) and three
   separate pure formatters in `ResumeHelper`
   (`formatMonitorNotificationsSection` / `formatDelegationNudgesSection` /
   `formatBackgroundCompletionsSection`, `ResumeHelper.kt:140-177`) re-implement the same
   "persist-while-idle, replay-on-resume" pattern three times.
4. **`iterationsSinceLastUser = 0` is reset for every drained item** (`AgentLoop.kt:956`),
   including non-user (monitor/background) events — corrupting the compaction heuristic that
   tracks how long the *user* has been silent.

**Asymmetry to highlight:** on the **idle/resume** path each source *is* framed correctly
(its own `ResumeHelper` section); on the **live** path everything is mis-framed as user
steering. The unified design makes **both paths use one source-owned framing function**, so
wording is identical and correct regardless of live-vs-idle.

### Goals

- One typed, source-aware envelope + one per-session queue carrying both live and idle items.
- Source-correct framing on both the live and resume paths (kill the mislabeling).
- Collapse the 3 persistence stores + 3 resume formatters into one.
- Priority ordering + per-source coalescing.
- A registry so a *future* feature adds a source by registering a policy — touching neither
  `AgentLoop` nor `AgentService`.

### Non-goals

- Changing the cross-IDE delegation wire protocol, the `IdleSessionWaker` guard
  (`AutoWakeGuardState` cooldown/cap/flood), or the monitor upstream coalescing
  (`MonitorManager.coalesceWindowMs`). Those stay; the queue sits *below* the wake decision
  and *after* monitor coalescing.
- Changing `ContextManager`, compaction, or persistence of `api_conversation_history.json`.
- Adding new producers in this work (only re-homing the four that exist).

---

## 2. Approved decisions (from brainstorming + review)

1. **Scope:** full redesign, all phases — typed queue + policy registry + priority + coalescing,
   covering **both** live and idle paths.
2. **Drain model:** *grouped by source, framed per source*. Each drain produces **one framed
   section per source kind**; same-source items merge into that section. The sections are
   concatenated (in drain order — see #3) into a **single** `addUserMessage` with a **single**
   `withEnvDetails` wrap (NOT one message per group — see §6 / review finding C2/C4: per-group
   `withEnvDetails` strips earlier-in-batch env blocks, and the downstream
   `MessageSanitizer` merges consecutive user messages anyway, so a single combined message is
   both correct and equivalent).
3. **Drain order:** **earliest-timestamp first** (preserves causal / referent order, matches
   today's FIFO), with the source **priority used only as a tiebreaker** when timestamps collide.
   Priority is therefore a tiebreaker weight, not a hard ordering — this avoids the within-drain
   *referent inversion* a reviewer found (a USER message that refers to an earlier BACKGROUND
   result must not be hoisted above it). Tiebreak priority: USER (100) > DELEGATION (70) >
   BACKGROUND (50) > MONITOR (30).

---

## 3. Core data model

New package `agent/loop/queue/`.

```kotlin
enum class QueueSourceKind { USER, DELEGATION, BACKGROUND, MONITOR }   // append a value = new source

data class QueuedMessage(
    val id: String,
    val kind: QueueSourceKind,
    val body: String,                       // raw payload, UNFRAMED
    val timestamp: Long,
    val priority: Int,                      // snapshotted from policy at enqueue
    val coalesceKey: String? = null,        // null = never coalesce
    val parts: List<ContentPart>? = null,   // user-path images/attachments (today's addUserMessageWithParts)
    val meta: Map<String, String> = emptyMap(), // bgId / monitorId / exitCode / delegationPeer …
)
```

`id` generation preserves today's shapes per source (user `steer-…`, async `auto-…`) so existing
UI promotion keying is unaffected.

---

## 4. Source policy + registry

```kotlin
interface QueueSourcePolicy {
    val kind: QueueSourceKind
    val priority: Int                                  // TIEBREAKER weight only (drain is timestamp-first)
    val resetsUserSilenceCounter: Boolean              // ONLY user = true (fixes iterationsSinceLastUser)
    val autoWakesIdle: Boolean                         // user n/a; async = true (still budget-guarded)
    val durable: Boolean                               // persist while pending? user=false, async=true
    val defersCompletion: Boolean                      // arriving mid-attempt_completion stream blocks exit?

    /** Coalescing key for an item; null = never coalesce. bg→bgId, monitor→monitorId. */
    fun coalesceKey(msg: QueuedMessage): String?

    /** Build the framed user-message text for a same-source group (priority-ordered drain). */
    fun frame(group: List<QueuedMessage>): String
}

object QueueSourceRegistry {
    private val policies = java.util.concurrent.ConcurrentHashMap<QueueSourceKind, QueueSourcePolicy>()
    fun register(p: QueueSourcePolicy)
    fun policyFor(kind: QueueSourceKind): QueueSourcePolicy
}
```

Default policy table:

| Policy field | USER | DELEGATION | BACKGROUND | MONITOR |
|---|---|---|---|---|
| `priority` (tiebreaker only) | 100 | 70 | 50 | 30 |
| `resetsUserSilenceCounter` | ✅ | ❌ | ❌ | ❌ |
| `autoWakesIdle` | n/a | ✅ | ✅ | ✅ |
| `durable` | ❌ (in-memory, as today) | ✅ | ✅ | ✅ |
| `defersCompletion` | ✅ | ✅ | ✅ | ❌ |
| `coalesceKey` | none | none | `bgId` (latest wins) | `monitorId` |

`frame()` produces source-correct wording on **both** the live and resume paths:

- **USER** → verbatim `STEERING_MESSAGE_PREFIX + items.joinToString("\n\n")` (byte-identical to
  today, so user steering behaviour is unchanged).
- **BACKGROUND** → the `ResumeHelper.formatBackgroundCompletionsSection` body shape, **plus an
  explicit action directive** (review finding S1): the bare resume-list body has no call to
  action, so reusing it verbatim live would make the agent *stop acting* on background results
  mid-task (today's user-prefix framing nudges it to act). `frame()` must append the
  `buildAutoResumeSyntheticMessage`-style "Decide whether this needs action; if it does, address
  it, otherwise continue your current task." directive so a live completion still prompts a decision.
- **DELEGATION** → the `formatDelegationNudgesSection` shape, with the same "decide whether this
  needs action / answer the question via `delegation(action=answer)`" directive (it already has one).
- **MONITOR** → the `formatMonitorNotificationsSection` shape (ambient; no action directive needed).

> The three `ResumeHelper.format*Section` functions become the bodies of the corresponding
> `frame()` implementations — moved, not deleted. **The canonical framing now lives in `frame()`;
> the resume path calls `frame()` too, so live and resume wording is identical.** The §10 parity
> test asserts each `frame()` output against the chosen canonical string (and the team accepts that
> *live* background/monitor/delegation wording shifts from the old user-prefix to the source body).

---

## 5. The queue

```kotlin
class UnifiedMessageQueue(
    private val sessionId: String,
    private val persistence: QueuePersistence?,   // null for sub-agents / tests (in-memory only)
) {
    fun enqueue(msg: QueuedMessage): EnqueueResult     // coalesce + (if durable) persist
    fun isEmpty(): Boolean
    fun drainGrouped(): List<DrainGroup>               // timestamp-asc (priority tiebreak), grouped, framed, cleared
    fun remove(id: String): Boolean                    // single-item cancel — see review finding C1
    fun pendingIds(): List<String>                     // for failure-promote (UI) without draining into context
    fun clear(ids: List<String>)
}

data class DrainGroup(
    val kind: QueueSourceKind,
    val framedText: String,                            // policy.frame(group) — already env-wrapped by caller
    val ids: List<String>,
    val resetsUserSilenceCounter: Boolean,
    val defersCompletion: Boolean,
)
```

- **Internal structure:** a guarded `ArrayList<QueuedMessage>`. The guard is a **plain JVM lock**
  (`ReentrantLock` / `synchronized`), **NOT** a coroutine `Mutex` — `enqueue`/`remove` are called
  synchronously from EDT (user typing, plan revise/dismiss) and arbitrary IO threads, so they must
  not suspend (review finding C4). `enqueue`, `remove(id)`, `clear`, `drainGrouped`, `isEmpty` all
  take the same lock; critical sections are short and never nested, so deadlock risk is nil. Drain
  sorts a snapshot by **`(timestamp asc, priority desc)`** — earliest-first with priority only as a
  tiebreaker (decision #3). Drains happen only at loop boundaries (low frequency), so sort-on-drain
  is cheaper and simpler than a live `PriorityBlockingQueue` — and a PBQ couldn't do coalesce-by-key
  replacement or stable timestamp ordering anyway. This single lock subsumes today's
  `ConcurrentLinkedQueue` lock-free `offer`/`poll`/`removeIf`/`clear` (`AgentController.kt:1146` is
  the `removeIf` → now `remove(id)`).
- **Coalescing (enqueue-time):** if `policy.coalesceKey(msg) != null` and an existing pending
  item shares that key, the existing item is **replaced** (latest-wins) rather than appended.
  Net new behaviour: two completions for the same `bgId` collapse to the latest. Monitor is
  mostly pre-coalesced upstream; queue-level monitor coalescing is a secondary guard.
- **Persistence:** `QueuePersistence` writes the pending list to **one** file per session,
  `sessions/{id}/pending_queue.json`, atomically (tmp + `ATOMIC_MOVE`, owner-only perms) — the
  exact shape `BackgroundPersistence` (`BackgroundPersistence.kt`) uses today. Only `durable`
  items are persisted; USER items live in memory only (preserving today's
  "steering is in-memory only" revert invariant — see `agent/CLAUDE.md` →
  "Steering-and-revert invariant"). Drain clears delivered ids from both memory and disk.

`QueuePersistence` replaces `DelegationNudgePersistence` and `BackgroundPersistence` (both
deleted) plus the three `ResumeHelper` formatters. **`MonitorPersistence` is NOT deleted**
(review finding A1 / CRITICAL): it has two jobs — the queue-relevant *notification* half
(`appendPendingNotification` / `loadPendingNotifications` / `clearPendingNotifications` →
`sessions/{id}/monitor-notifications.json`) **and** the unrelated *`MonitorSpec`* half
(`load`/`add`/`remove`/`clear` → `sessions/{id}/monitors.json`) that drives **resume re-arm** of
live watchers (`AgentMonitorCoordinator.reArmMonitors`). Only the **notification half** is absorbed
into `QueuePersistence`; the `MonitorSpec` half stays exactly as-is. Note the three legacy
notification/nudge/completion files live in *different* dirs and schemas
(`sessions/{id}/background/pending_completions.json` typed events;
`sessions/{id}/background/pending_delegation_nudges.json` typed nudges-with-ids;
`sessions/{id}/monitor-notifications.json` bare `List<String>`, no ids) — see §9 for how they retire.

---

## 6. Live drain integration (Stage 0.5 rewrite)

`AgentLoop` swaps its `steeringQueue: ConcurrentLinkedQueue<SteeringMessage>?` field for
`messageQueue: UnifiedMessageQueue?`. Stage 0.5 (`AgentLoop.kt:947-960`) becomes:

```kotlin
if (messageQueue != null && !messageQueue.isEmpty()) {
    val groups = messageQueue.drainGrouped()              // timestamp-asc (priority tiebreak), grouped, framed, cleared
    if (groups.isNotEmpty()) {
        val combined = groups.joinToString("\n\n") { it.framedText }   // sections in drain order
        contextManager.addUserMessage(withEnvDetails(combined))        // SINGLE message, SINGLE env-wrap
        if (groups.any { it.resetsUserSilenceCounter }) iterationsSinceLastUser = 0   // ONLY if a USER group present
        onSteeringDrained?.invoke(groups.flatMap { it.ids })           // UI promotion unchanged
    }
}
```

- **One combined `addUserMessage` with one `withEnvDetails`** (review findings C2/C4): per-group
  wrapping is wrong because `withEnvDetails` → `stripStaleEnvironmentDetails()` strips the env block
  from messages already added earlier in the same batch, and the downstream `MessageSanitizer`
  merges consecutive user turns anyway. The combined message still presents **per-source framed
  sections in drain order**, satisfying the grouped-by-source decision without the env-duplication
  or strip-on-next-iteration bug.
- `enqueueSteeringMessage(text)` (`AgentLoop.kt:1828`) is kept as a **thin back-compat shim**
  during migration: `messageQueue?.enqueue(QueuedMessage(kind = USER-or-caller-supplied, …))`.
  Callers migrate to a typed `enqueue(kind, body, meta)` and the shim is removed in Phase 3.

### Exit-drain & failure-promote

The two exit helpers (`drainSteeringIntoContextOnExit` `AgentLoop.kt:~2686`,
`promoteSteeringQueueOnFailure` `AgentLoop.kt:~2707`) are preserved with one refinement:

- **Exit drain** (mid-`attempt_completion`/`new_task` stream): only **defer exit** if the
  drained set contains a group with `defersCompletion = true`. So a user message or a
  delegation reply arriving during the final stream still blocks the exit (today's behaviour for
  user); an *ambient monitor blip* (`defersCompletion=false`) is injected but does **not** keep a
  finished task alive. Groups are still injected via `addUserMessage` regardless.
- **Failure promote** (doom loop / exhausted retries): `messageQueue.pendingIds()` → fire
  `onSteeringDrained` for UI promotion **without** injecting into context (unchanged).

---

## 7. Idle auto-wake + resume unification

### Producers (live-vs-idle collapses)

Each of the three async coordinators stops branching on `activeLoopForSession`. They call
`agentService.enqueueToSession(sessionId, QueuedMessage(...))`, which:

```kotlin
fun enqueueToSession(sessionId: String, msg: QueuedMessage) {
    val queue = queueForSession(sessionId)   // per-session, created with session
    queue.enqueue(msg)                        // persists if durable
    if (activeLoopForSession(sessionId) == null) {       // idle → wake decision
        val policy = QueueSourceRegistry.policyFor(msg.kind)
        if (policy.autoWakesIdle) autoWakeIdleSession(sessionId, syntheticText = "", source = msg.kind.name)
    }
    // live loop will drain at next Stage 0.5; no explicit hand-off needed
}
```

- **The wake stays a blank-synthetic wake** (as delegation does today, `AgentService.kt:505`):
  the persisted queue item is the single delivery carrier, so resume replays it exactly once and
  adds no duplicate "User message on resume" line. `IdleSessionWaker` + `AutoWakeGuardState`
  (cooldown / cap / flood / no-listener) are unchanged and still shared across all sources.
- On `SKIP_GUARD` / `DEFER` the item simply stays persisted until the next resume — same
  durability guarantee the three stores give today.
- **This fixes a latent background double-delivery bug** (review finding, HIGH). Today the
  background path wakes with a **non-blank** `buildAutoResumeSyntheticMessage` AND persists the same
  event (`BackgroundCompletionCoordinator.kt:73,78`), so on a successful auto-wake `resumeSession`
  delivers it **twice** (once as the synthetic "User message on resume", once via
  `formatBackgroundCompletionsSection`). The blank-wake + queue-as-sole-carrier design delivers it
  exactly once. ⚠ **Phase-skew risk:** the blank-wake switch and the queue-replay-on-resume must
  land **atomically** in Phase 2 — if blank-wake ships without queue replay, a background completion
  is delivered **zero** times. Pin single-delivery with a test.

### Resume

`resumeSession` stops calling the three `format*Section` helpers. Instead:

```kotlin
val groups = queueForSession(sessionId).drainGrouped()   // same framing as live
val awaySections = groups.joinToString("\n") { it.framedText }
// append awaySections to ResumeHelper.buildTaskResumptionPreamble(...) output
```

So the `[TASK RESUMPTION]` preamble (`ResumeHelper.buildTaskResumptionPreamble`,
`ResumeHelper.kt:105`) is followed by the **same per-source framed text** the live path would
have shown (sections in drain order). Drain consumes + clears, exactly once. Duplication gone.
Note this changes the resume-section *order* from today's fixed BACKGROUND→DELEGATION→MONITOR to
timestamp order — semantically harmless, but pin it as an accepted change, not byte-parity.

---

## 8. UI rendering

- **USER** keeps today's pill → bubble promotion: `dashboard.addQueuedSteeringMessage` on
  enqueue, `onSteeringDrained` → `promoteQueuedSteeringMessages(ids)` on drain (unchanged
  bridge chain `AgentController → AgentDashboardPanel → AgentCefPanel`).
- **Async sources** (monitor/delegation/background): today they are invisible in chat until the
  LLM reacts. *Optional improvement (flagged for review):* render a compact, non-bubble "event"
  card on enqueue keyed by `kind` (e.g. "Background process finished", "Monitor: …") so the
  user sees the injected event. This is additive and can ship in a later phase; the core refactor
  does not require it.

---

## 9. Migration plan (phased, behaviour-preserving first)

**Phase 0 — pure core, no wiring.** Add `QueuedMessage`, `QueueSourceKind`,
`QueueSourcePolicy`, `QueueSourceRegistry`, `UnifiedMessageQueue`, `QueuePersistence`, and the 4
default policies. Unit-test in isolation (priority, coalescing, drain grouping, framing parity).
Nothing references them yet.

**Phase 1 — live path swap, USER only, behaviour-identical.** Replace `AgentLoop`'s
`steeringQueue` with `messageQueue`. `UserPolicy.frame` reproduces `STEERING_MESSAGE_PREFIX`
verbatim. `enqueueSteeringMessage` shim enqueues `kind=USER`. Verify the existing steering
behaviour (Stage 0.5, exit-drain, failure-promote, UI promotion) is byte-identical. Async
producers still use their old idle stores — untouched.

**Phase 2 — migrate async producers + collapse idle stores.** Point monitor/delegation/
background producers at `enqueueToSession` with their typed `kind`. Move the three
`ResumeHelper.format*Section` bodies into the corresponding `frame()`. New async producers write
**only** `pending_queue.json`. Route `resumeSession`'s queue replay through `drainGrouped()`.
Delete `DelegationNudgePersistence` + `BackgroundPersistence`; keep `MonitorPersistence`'s
`MonitorSpec` half (§5). Land the blank-wake switch (§7) atomically with the queue replay.

> **No one-time "fold" (corrected per review — CRITICAL).** An in-place fold of legacy files into
> `pending_queue.json` is not idempotent (it must delete the source atomically, reconcile three
> different schemas, and synthesize ids for the id-less monitor `List<String>` — a re-run
> double-delivers). Instead, **drain legacy stores on resume for one release**: keep the existing
> `resumeSession` legacy readers (which already load **and consume** `pending_completions.json` /
> `pending_delegation_nudges.json` / `monitor-notifications.json`) for one version, alongside the
> new `pending_queue.json` replay. A session mid-upgrade has its old events delivered+consumed by
> the old reader and its new events by the queue — no fold, no idempotency proof, legacy files
> drain to empty within one resume. The legacy readers retire in the *next* release.

**Phase 3 — remove shims + add capabilities.** Remove `enqueueSteeringMessage`; callers use
typed `enqueue`. Turn on coalescing + the `defersCompletion` exit refinement. Optionally add the
async-event UI cards (§8).

Each phase is independently shippable and testable; Phases 0–1 carry zero behaviour change.

---

## 10. Testing strategy

The module's hard-won test constraints (from `agent/CLAUDE.md` + memory) shape this:

- `AgentService`/`AgentLoop` are **not** unit-instantiable → integration is pinned by
  **source-text contract tests** (e.g. "Stage 0.5 resets the counter only for USER groups",
  "exit-drain defers only when a `defersCompletion` group is present").
- The **pure** pieces carry the real coverage (mirrors the walkthrough validator/clamp and
  monitor `BambooDiff`/`ListDiff` precedent): `UnifiedMessageQueueTest` (priority order,
  coalescing latest-wins, grouped drain, durable-vs-memory), `QueueSourcePolicyTest` (framing
  parity — assert `UserPolicy.frame` equals the old `STEERING_MESSAGE_PREFIX` output;
  Background/Delegation/Monitor `frame` equal the old `ResumeHelper` sections),
  `QueuePersistenceTest` (atomic round-trip + the one-time migration fold).
- **Only ONE `BasePlatformTestCase` per test JVM** (the indexing-timeout trap, issue #51) — the
  queue needs none; keep everything pure/headless.
- Watch the **build-cache + suspend-signature trap**: if any lambda flips to/from `suspend`
  during wiring, run `:agent:test --rerun --no-build-cache`.
- Watch the **source-text sentinel-slice trap**: place new `AgentLoop`/`AgentService` functions
  outside any range a contract test slices between function-name sentinels; run full
  `:agent:test`, not just `--tests`. Confirmed live: `DelegationConversationNarrationTest` slices
  `AgentService.kt` between `fun delegatedIncomingTaskText` and `fun mapLoopResultToDelegationResult`
  — keep `enqueueToSession`/`queueForSession` out of that range.

**Existing-test blast radius (must be edited in lockstep — reviewer-verified):**
- `SessionUiCallbacksParityTest:93` hard-codes the string `"steeringQueue"` as a required bundle
  field → fails the instant the field is renamed to `messageQueue`; update the required-list +
  the field type in the same commit.
- `AgentLoopExitDrainTest` constructs `ConcurrentLinkedQueue<SteeringMessage>()` + `SteeringMessage(...)`
  ~20× and drives `drainSteeringIntoContextOnExit` / `promoteSteeringQueueOnFailure` → won't compile
  after the field/type swap; port to `UnifiedMessageQueue` + `QueuedMessage(kind=USER)`.
- `BackgroundCompletionCoordinatorTest` pins `buildCompletionSystemMessage` /
  `buildAutoResumeSyntheticMessage`; if Phase 2 moves background framing into `frame()` / drops the
  synthetic builder (blank-wake no longer needs it), this test moves with it.
- `AgentControllerSteeringFlushTest` exercises the controller-side flush; re-verify after the rename.
- `MessageStateHandlerTruncateTest` (the revert-invariant pin) must stay GREEN untouched — treat it
  as the acceptance gate that no USER queue item leaked into `api_conversation_history.json`.

---

## 11. Risks & open questions

1. **`defersCompletion` semantics** (OPEN) — a delegation *reply* (not a question) arriving during
   the final `attempt_completion` stream defers exit (DELEGATION `defersCompletion=true`); a monitor
   blip does not (MONITOR=false). A reviewer flagged that a delegation FYI-result forcing a finished
   task to stay alive can cost a spurious extra turn. Default kept (defer for USER/DELEGATION/
   BACKGROUND, not MONITOR); revisit if the extra-turn shows up in smoke. Pinned by a source-text
   contract test ("exit defers only when a `defersCompletion` group is present").
2. **Persisting USER steering** (RESOLVED) — kept in-memory (`durable=false`) to preserve the
   revert invariant (which is about `api_conversation_history.json` / `conversationHistoryIndex`,
   NOT a separate `pending_queue.json` — reviewer-confirmed sufficient) and today's crash semantics.
   The drain MUST stay on `ContextManager.addUserMessage` (never `addToApiConversationHistory`) or
   the invariant breaks — one-line guard noted for future contributors.
3. **Combined message + sanitizer merge** (RESOLVED) — decision #2/§6: drain emits ONE combined
   `addUserMessage` (per-source sections in drain order) with ONE `withEnvDetails`. Reviewer traced
   `MessageSanitizer.sanitizeForAnthropic` Phase 2 — consecutive-user merge joins `content` with
   `"\n\n"` in list order, so framing + order survive; the single-message form sidesteps the
   env-duplication the per-group form would cause.
4. **Legacy persistence retirement** (RESOLVED → §9) — replaced the non-idempotent one-time fold
   with drain-legacy-on-resume-for-one-release.
5. **Coalescing a background `bgId` latest-wins** (RESOLVED) — reviewer checked every
   `BackgroundCompletionEvent` consumer; a `bgId` exits once, the persisted consumer already keys on
   `bgId`, nothing needs both. Safe, no flag.
6. **Drain ordering** (RESOLVED → decision #3) — timestamp-asc with priority tiebreak fixes the
   within-drain referent-inversion. *Across* drains, an item enqueued at boundary N is still
   delivered at N even if a later item would sort earlier — intended (don't starve / don't reorder
   across turns).

---

## 12. Files touched (inventory)

**New:** `agent/loop/queue/{QueuedMessage,QueueSourceKind,QueueSourcePolicy,QueueSourceRegistry,UnifiedMessageQueue,QueuePersistence}.kt` + 4 policy classes.

**Modified:** `AgentLoop.kt` (field swap `steeringQueue`→`messageQueue`, Stage 0.5, exit helpers,
shim), `AgentService.kt` (`enqueueToSession`, `queueForSession`, resume wiring incl. one-release
legacy drain, `enqueueNudgeForSession`), `AgentMonitorCoordinator.kt`,
`BackgroundCompletionCoordinator.kt` (blank-wake; drop `buildAutoResumeSyntheticMessage`),
`DelegationInboundService.kt`, `ResumeHelper.kt` (move 3 formatters into `frame()`; keep preamble),
`MonitorPersistence.kt` (remove only the *notification* half; keep `MonitorSpec` half),
`SessionUiCallbacks.kt` (`steeringQueue` field+type → `messageQueue`), `AgentController.kt`
(queue construction/threading; the 6 enqueue sites incl. `revisePlan:4927` + `performPlanDiscard:4959`
+ pill-cancel `removeIf:1146` → `remove(id)`).

**Deleted:** `DelegationNudgePersistence.kt`, `BackgroundPersistence.kt`, the 3
`ResumeHelper.format*Section` methods, and (next release) the one-release legacy resume readers.
**NOT deleted:** `MonitorPersistence.kt` (its `MonitorSpec` half survives — see §5).

**Docs:** update `agent/CLAUDE.md` ("Real-Time Steering", "Monitor Framework" persistence,
delegation auto-delivery, background completion) in the same commit as the architecture change.

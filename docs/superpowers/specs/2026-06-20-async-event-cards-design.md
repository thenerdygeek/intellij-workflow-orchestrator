# Async Event Cards (timeline visuals for background + monitor) — Design Spec

**Date:** 2026-06-20
**Module:** `:agent` (Kotlin producers/persistence + `agent/webview` React)
**Status:** Reviewed (2-reviewer panel, code-verified) → revised. Pending user sign-off.
**Related:** builds on the unified message queue (`docs/superpowers/specs/2026-06-19-unified-message-queue-design.md`)

> **Review pass (2026-06-20).** Two adversarial reviewers (Kotlin/integration + webview/UX)
> verified the spec against live code. Verdict: *sound concept, fixable pre-implementation.* Folded
> in: the delegation persistence helper is `appendDelegationCardToSession` and is **active-session-
> only** (no idle write path) → idle/non-focused events are now handled **queue-backed** (durable in
> `pending_queue.json`, card synthesized on resume); `COMPACTION_MARKER` is **not** persisted (live-
> only) so only `DELEGATION_CARD` is the persistence model; `BackgroundCompletionEvent` also carries
> `kind`/`sessionId`/`occurredAt`; the monitor `monitorId` *is* available at `MonitorManager.flushDue`
> (widen the callback signature — doesn't disturb wake-budget); align the status-dot colors to the
> existing **amber** convention. Confirmed sound: the JSON-string+`JSON.parse` bridge contract (not
> `coerceSnapshotArray`), the card-over-inline-note choice (not YAGNI), and the renderItem/store/type
> extensions.

---

## 1. Problem & context

When a **background process finishes** or a **monitor event fires**, the resulting `QueuedMessage`
is injected into the agent's LLM context (correctly framed, per the unified-queue work) — but
**nothing renders in the chat timeline**. The user only infers the event from the agent's later
reply. The chat already renders rich timeline cards for other non-message events
(`COMPACTION_MARKER`, sub-agent cards, `COMPLETION_RESULT`, and **`DELEGATION_CARD`** — cross-IDE
delegation results already show as cards), so background/monitor are the conspicuous gap.

**Two asks, one already resolved:**
1. **Top-bar "active bg/monitor view"** — the `BackgroundIndicator`/`MonitorIndicator` chips
   already exist but were **always empty** due to a bridge bug (JSON-string snapshot treated as a
   JS array). **FIXED** in commit `866f00db3` (`coerceSnapshotArray` at the 4 bridge boundary
   sites). Not part of this spec; recorded for context. (An optional "always-visible idle state"
   for the chips is a separate future add-on, out of scope here.)
2. **Timeline event cards** for background + monitor — **this spec.**

### Decisions (from brainstorming)

- **Trigger:** real-time, at the **event** (producer-sourced), so an idle session's completion
  still produces a card the instant it happens — not deferred to the drain.
- **Form:** compact card, **collapsed by default, click to expand** (output tail / event lines).
- **Persistence:** **persisted** to the session's `ui_messages` (UI-only — never to the LLM), so
  reopening shows the cards in order. Mirrors `DELEGATION_CARD`/`COMPACTION_MARKER`.
- **Scope:** **background completions + monitor events only.** Delegation already renders
  `DELEGATION_CARD`; adding it would double-up.

### Non-goals

- No change to the queue/framing path — the agent still receives the framed `QueuedMessage`
  unchanged. The card is a **parallel, human-facing artifact**; zero LLM-token cost, no
  double-injection.
- No change to the top-bar chips (already fixed) or their hide-when-empty behavior.
- No delegation card changes.

---

## 2. Data model

A new UI-only timeline item, parallel to `COMPACTION_MARKER`:

```typescript
// agent/webview/src/bridge/types.ts
UiSay += 'ASYNC_EVENT'
interface UiMessageAsyncEventData {
  kind: 'background' | 'monitor'
  sourceId: string            // bgId / monitorId
  label: string               // "npm run build" / monitor description
  status: 'success' | 'failure' | 'notable' | 'alert'   // → status-dot color
  summary: string             // collapsed one-liner: "exit 0 · 12.4s" / "2 new errors"
  details: string             // expanded body: output tail (bg) / event lines (monitor)
  timestamp: number
  spillPath?: string | null   // background: full-output file, if any
}
```

The Kotlin `UiSay` enum gains `ASYNC_EVENT` and `UiMessage` gains an `asyncEventData` payload field
(serialized into `ui_messages.json`), the way the `DELEGATION_CARD` `delegationCardData` payload is
carried (`UiMessage.kt`). **Note (review):** `COMPACTION_MARKER` is **not** a model here — it has no
Kotlin `UiMessage` field and is never persisted (it's pushed live via `callJs("insertCompactionMarker")`
and re-fires on resume). `DELEGATION_CARD` is the sole persistence precedent. **UI-only:** these
`UiMessage`s go through `MessageStateHandler.addToClineMessages` (→ `ui_messages.json`) and **never**
`addToApiConversationHistory` (→ `api_conversation_history.json`) — they are invisible to the LLM,
exactly like `DELEGATION_CARD`.

---

## 3. Producer-sourced emission (real-time)

A pure, unit-testable seam `AsyncEventCardPresenter` maps each producer's native event to a
`UiMessageAsyncEventData`:

- **Background** — `BackgroundCompletionCoordinator.onBackgroundCompletion(event)` holds a clean
  `BackgroundCompletionEvent` (bgId, **kind, sessionId**, label, exitCode, state, runtimeMs,
  tailContent, spillPath, **occurredAt** — `sessionId` is the routing key). Status:
  `state==SUCCESS && exitCode==0` → `success`, else `failure`. summary = `"exit N · {runtime}"`,
  details = tail. Emitted right where it enqueues — so the producer fires for live **and** idle
  sessions; whether it persists/pushes now or defers to resume is the §4 routing decision.
- **Monitor** — at `AgentMonitorCoordinator`'s delivery point. **Correction (review):** `monitorId`
  IS available where the callbacks fire — `MonitorManager.flushDue` iterates `for ((id, p) in ready)`
  per-monitor (no multi-monitor batching), `id` is the monitorId; only the callback *signature*
  `(String) -> Unit` hides it. **Fix:** widen `deliverToLoop`/`wakeIdle` to
  `(monitorId, severity, text)`. The wake-budget/flood accounting lives inside `flushDue` keyed on
  the loop `id` and is independent of the callback signature, so `WakeOutcome` is unaffected
  (confirmed against `MonitorManager`). One card per delivered (already-coalesced, per-monitor)
  batch: `details` = coalesced text, `sourceId` = monitorId, `status` from severity
  (`ALERT`→`alert`, `NOTABLE`→`notable`).

The presenter output flows to the bridge+persistence layer (§4). The framing/queue path is
untouched — this is purely additive at the producer.

---

## 4. Bridge & persistence routing (revised per review)

The existing `AgentService.appendDelegationCardToSession` (`AgentService.kt:538`) is
**active-session-only** — it no-ops unless the active `MessageStateHandler` exists *and* its
`sessionId == target`. So we do NOT have (and will not build) a writer into a dormant session's
`ui_messages.json`. Instead, routing splits by whether the event's session is the focused one:

- **Focused session** (active `MessageStateHandler` exists and `handler.sessionId == event.sessionId`):
  (1) live push via a new fire-and-forget `callJs("_pushAsyncEventCard(<json>)")`, and (2)
  append+persist the `UiMessage` via the active handler (`addToClineMessages` only — never API
  history), the same seam `appendDelegationCardToSession` uses.
- **Idle / non-focused session** (decision: *queue-backed*): **nothing is written at event time.**
  The event is already durably persisted by the unified queue in `pending_queue.json`
  (`BACKGROUND`/`MONITOR` are `durable=true`). When that session is next opened/resumed,
  `AgentService.resumeSession` already drains `queueForSession(sid).drainGrouped()` — at that point
  we **also synthesize an `ASYNC_EVENT` card per drained `BACKGROUND`/`MONITOR` group** and append it
  via the now-active handler. The card thus appears in order the moment the user views the session,
  with no dormant-file write and no concurrent-write race.
  - *Fidelity:* the queued item carries `body` (framed text) + `meta` (bgId/monitorId). To rebuild a
    faithful card at resume, the producer stashes a compact `AsyncEventCardData` JSON in the
    `QueuedMessage.meta["card"]` at enqueue time; resume reads it back. (Fallback if absent: derive
    `details=body`, `summary`=first line — lower fidelity, still useful.)

**Bridge contract (review-confirmed):** `_pushAsyncEventCard` passes the payload as a **single JSON
object string**; the handler **`JSON.parse`s it inside try/catch** then calls
`chatStore.addAsyncEventCard(payload)`. This matches the file's "Convention A" structured-push
bridges (`_appendDelegatedResult`, `_appendCompletionCard`). `coerceSnapshotArray` is **not** used
here — that helper was for the array/string snapshot case; a single object only needs `JSON.parse`.
A test feeds a JSON string through the handler so this path can't regress into the string-vs-array
defect that hid the top-bar chips. *Known accepted limitation:* `_pushAsyncEventCard` (an
`initBridge`-registered underscore global) bypasses the `pendingCalls` buffer, so a push that
arrives before React mounts is dropped live — but the persisted card hydrates on load, so nothing is
lost (same gap as the delegation-card pushes).

New/changed wiring: `globals.d.ts` (+`_pushAsyncEventCard`), `jcef-bridge.ts` (handler →
`addAsyncEventCard`), `chatStore.ts` (`addAsyncEventCard` appends a `UiMessage` like
`appendDelegatedResult`), `AgentCefPanel`/`AgentDashboardPanel` (`pushAsyncEventCard`), and the
Kotlin emit/persist seam on `AgentService` (focused-session push+append; resume-drain card synthesis).

---

## 5. React component

`<AsyncEventCard>` (`agent/webview/src/components/agent/AsyncEventCard.tsx`), rendered from a new
`ChatView.renderItem` branch for `say==='ASYNC_EVENT'`:

- **Collapsed:** source icon (gear=background, eye=monitor — reuse the indicator icons) · source
  badge · status dot · one-line `summary` · timestamp · ▸ chevron. **Status-dot colors (review —
  match the existing indicator convention):** `success`→green (`--accent-write`), `failure`→amber
  (`--accent-edit`, the color `BackgroundIndicator` already uses for bad exits), `notable`→muted
  (`--fg-secondary`), `alert`→red (`--error`) as the one deliberate, documented divergence (a monitor
  ALERT is the most severe signal and should read as red).
- **Expanded:** `details` in a monospace block (output tail / event lines); background shows an
  "open full output" affordance when `spillPath` is set. User-initiated expand/collapse (like the
  indicator dropdowns) — NOT auto-collapse.
- Reuses the steering-pill + indicator-dropdown styles and JB CSS tokens; light + dark; fade-in.
- *Note:* an `ASYNC_EVENT` item between two `TOOL` messages breaks `ChatView`'s consecutive-tool
  grouping at that timestamp — identical to how `DELEGATION_CARD`/`COMPACTION_MARKER` behave today.
  Accepted.

---

## 6. Testing

- **Pure Kotlin:** `AsyncEventCardPresenterTest` — `BackgroundCompletionEvent`→payload (status
  mapping for exit 0 / non-zero / failed state; summary/details derivation), monitor
  batch→payload (severity→status). Pure, headless. (The presenter MAY be a pure companion on
  `BackgroundCompletionCoordinator` rather than a new top-level class — either is fine.)
- **Focused-session persistence contract:** the card `UiMessage` lands in `ui_messages.json` via
  `addToClineMessages` but **not** `addToApiConversationHistory`, for the focused session, and
  reloads on session open. Source-text/contract test mirroring `appendDelegationCardToSession`
  (these AgentService paths aren't unit-instantiable).
- **Resume-synthesis contract:** on resume, each drained `BACKGROUND`/`MONITOR` queue group yields
  an `ASYNC_EVENT` card appended to the resumed session (the queue-backed idle path). Pure-test the
  `meta["card"]`→payload rebuild + fallback; source-text pin the resume hook.
- **Webview:** `AsyncEventCard.test.tsx` (collapsed/expanded, bg vs monitor, status colors, empty
  details, `spillPath` affordance) + a `chatStore.addAsyncEventCard` test that feeds the payload as
  a **JSON object string** through the `_pushAsyncEventCard` handler (locks out the string-vs-array
  bug class), surviving `ResumeHelper.trimResumeMessages` on resume.

---

## 7. Open questions & risks

1. **Monitor callback widening (§3) — RESOLVED.** Reviewer confirmed `monitorId` is available at
   `MonitorManager.flushDue` (`for ((id,p) in ready)`) and widening `deliverToLoop`/`wakeIdle` to
   `(monitorId, severity, text)` doesn't touch the wake-budget accounting (keyed on the loop `id`,
   independent of the callback signature). Real work across `MonitorManager`/`AgentMonitorCoordinator`
   + tests, but feasible and safe.
2. **Idle persistence races — RESOLVED (design changed).** No dormant-file writer. Idle/non-focused
   events ride the unified queue's existing durability (`pending_queue.json`) and become cards on
   resume via the active handler. The risky concurrent-write path the reviewer flagged is gone.
3. **Resume fidelity (new).** The resume-synthesized card rebuilds from `meta["card"]` (stashed at
   enqueue) for full fidelity, or falls back to `body`/first-line. Confirm the `meta` stash doesn't
   bloat the queue file unreasonably (compact payload only).
4. **Coalescing vs cards.** A per-monitor coalesced batch = one card. A background `bgId` completes
   once → one card. No flood (and the monitor flood guard auto-stops at 20/min upstream).
5. **Compaction / resume survival — confirmed.** `ui_messages` cards aren't in the LLM context, and
   `ResumeHelper.trimResumeMessages` only strips trailing resume/cost-less messages — `DELEGATION_CARD`
   survives resume today, so `ASYNC_EVENT` will too.
6. **Volume / scroll.** `MESSAGES_HARD_CAP=1000` bounds the live heap; `ui_messages.json` itself is
   unbounded (loads all, then evicts to cap) — a pre-existing characteristic shared with delegation
   cards. Collapsed-by-default keeps them compact; no pagination (YAGNI).

---

## 8. Files touched (inventory)

**Kotlin:** `AsyncEventCardPresenter` (new pure unit — standalone class or a companion on
`BackgroundCompletionCoordinator`), `BackgroundCompletionCoordinator.kt` (emit + stash `meta["card"]`),
`MonitorManager.kt` + `AgentMonitorCoordinator.kt` (widen `deliverToLoop`/`wakeIdle` to
`(monitorId, severity, text)`; emit + stash), `AgentService.kt` (focused-session push+append seam,
sibling of `appendDelegationCardToSession`; **resume-drain → card synthesis** in `resumeSession`),
`AgentCefPanel.kt`/`AgentDashboardPanel.kt` (`pushAsyncEventCard`), the Kotlin `UiSay`/`UiMessage`
model (+`ASYNC_EVENT`, `asyncEventData`).

**Webview:** `bridge/types.ts` (+`ASYNC_EVENT`, `UiMessageAsyncEventData`), `bridge/globals.d.ts`,
`bridge/jcef-bridge.ts` (handler), `stores/chatStore.ts` (`addAsyncEventCard`),
`components/agent/AsyncEventCard.tsx` (new), `components/chat/ChatView.tsx` (renderItem branch).
